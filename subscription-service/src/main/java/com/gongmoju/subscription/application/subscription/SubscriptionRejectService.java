package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionRejectService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-margin-reserve-failed";
    public static final String TOPIC = "margin.reserve-failed";

    private final ProcessedEventRepository processedEventRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 증거금 확보 실패 응답을 처리함 (이벤트 명세 {@code margin.reserve-failed} 절). 처리 기록, 청약 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * 증거금이 인출되지 않았으므로 보상({@code margin.void-requested})은 기록하지 않음.
     * <p>
     * 받은 때의 청약 상태에 따라 처리가 다름.
     * <ul>
     *   <li>{@code PENDING}: 거절 사유를 저장하고 {@code REJECTED}로 전환함</li>
     *   <li>{@code REJECTED}: 중복 응답이므로 로그만 남기고 정상 종료함</li>
     *   <li>그 외: 정상적으로는 오지 않는 응답이므로 에러 로그를 남기고 상태는 바꾸지 않음</li>
     * </ul>
     *
     * @param event 확보 실패 응답 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}
     */
    @Transactional
    public ReserveFailedResult reject(MarginReserveFailed event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, subscriptionId={}", event.eventId(), event.subscriptionId());
            return ReserveFailedResult.DUPLICATE_EVENT_IGNORED;
        }
        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + event.subscriptionId()));

        return switch (subscription.getStatus()) {
            case PENDING -> rejectPending(subscription, event);
            case REJECTED -> {
                log.info("이미 거절된 청약의 중복 응답이라 무시함. subscriptionId={}", subscription.getId());
                yield ReserveFailedResult.ALREADY_REJECTED_IGNORED;
            }
            case CONFIRMED, COMPENSATING, CANCEL_REQUESTED, CANCELLED, ALLOCATED, REFUNDED -> {
                log.error("정상적으로는 오지 않는 증거금 확보 실패 응답을 받음. 상태를 바꾸지 않음. subscriptionId={}, status={}, reason={}",
                        subscription.getId(), subscription.getStatus(), event.reason());
                yield ReserveFailedResult.UNEXPECTED_STATE_IGNORED;
            }
        };
    }

    private ReserveFailedResult rejectPending(Subscription subscription, MarginReserveFailed event) {
        subscription.rejectByMarginFailure(event.reason());
        log.info("청약을 거절함. subscriptionId={}, reason={}", subscription.getId(), event.reason());
        return ReserveFailedResult.REJECTED;
    }
}
