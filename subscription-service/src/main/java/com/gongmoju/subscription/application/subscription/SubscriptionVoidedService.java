package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
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
public class SubscriptionVoidedService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-margin-voided";
    public static final String TOPIC = "margin.voided";

    private final ProcessedEventRepository processedEventRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 증거금 해제 완료 응답을 처리함 (이벤트 명세 {@code margin.voided} 절). 처리 기록, 청약 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * <p>
     * 받은 때의 청약 상태에 따라 처리가 다름.
     * <ul>
     *   <li>{@code COMPENSATING}, {@code CANCEL_REQUESTED}: {@code CANCELLED}로 전환함</li>
     *   <li>{@code CANCELLED}: 중복 응답이므로 로그만 남기고 정상 종료함</li>
     *   <li>그 외: 정상적으로는 오지 않는 응답이므로 에러 로그를 남기고 상태는 바꾸지 않음</li>
     * </ul>
     *
     * @param event 해제 완료 응답 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}
     */
    @Transactional
    public VoidedResult complete(MarginVoided event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, subscriptionId={}", event.eventId(), event.subscriptionId());
            return VoidedResult.DUPLICATE_EVENT_IGNORED;
        }
        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + event.subscriptionId()));

        return switch (subscription.getStatus()) {
            case COMPENSATING, CANCEL_REQUESTED -> completeCancellation(subscription, event);
            case CANCELLED -> {
                log.info("이미 취소된 청약의 중복 응답이라 무시함. subscriptionId={}", subscription.getId());
                yield VoidedResult.ALREADY_CANCELLED_IGNORED;
            }
            case PENDING, CONFIRMED, REJECTED, ALLOCATED, REFUNDED -> {
                log.error("정상적으로는 오지 않는 증거금 해제 응답을 받음. 상태를 바꾸지 않음. subscriptionId={}, status={}, amount={}",
                        subscription.getId(), subscription.getStatus(), event.amount());
                yield VoidedResult.UNEXPECTED_STATE_IGNORED;
            }
        };
    }

    private VoidedResult completeCancellation(Subscription subscription, MarginVoided event) {
        if (isUnexpectedAmount(subscription, event.amount())) {
            // 정상 흐름에서는 생길 수 없음 (청약, 계좌 서비스 중 한쪽 문제). 취소 완료는 진행하되 확인 대상으로 에러 로그를 남김
            log.error("해제 금액이 청약의 증거금과 다름. 취소 완료는 진행함. subscriptionId={}, cancelReason={}, expectedAmount={}, voidedAmount={}",
                    subscription.getId(), subscription.getCancelReason(), subscription.getMarginAmount(), event.amount());
        }
        subscription.completeCancellation();
        log.info("청약을 취소 완료함. subscriptionId={}, cancelReason={}, voidedAmount={}",
                subscription.getId(), subscription.getCancelReason(), event.amount());
        return VoidedResult.CANCELLED;
    }

    // 시간 초과(TIMEOUT) 해제는 확보 여부를 모르고 보내므로 해제 금액이 0인 것도 정상임. 그 외에는 청약의 증거금과 같아야 함
    private boolean isUnexpectedAmount(Subscription subscription, long amount) {
        if (amount == subscription.getMarginAmount()) {
            return false;
        }
        return !(subscription.getCancelReason() == CancelReason.TIMEOUT && amount == 0);
    }
}
