package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
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
public class SubscriptionConfirmService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-margin-reserved";
    public static final String TOPIC = "margin.reserved";

    private final ProcessedEventRepository processedEventRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 증거금 확보 성공 응답을 처리함 (이벤트 명세 {@code margin.reserved} 절). 처리 기록, 청약 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * <p>
     * 받은 때의 청약 상태에 따라 처리가 다름.
     * <ul>
     *   <li>{@code PENDING}: {@code CONFIRMED}로 전환함</li>
     *   <li>{@code CONFIRMED} 이후: 중복 응답이므로 로그만 남기고 정상 종료함</li>
     *   <li>{@code COMPENSATING}, {@code CANCELLED}, {@code REJECTED}: 정상적으로는 오지 않는 응답이므로 에러 로그를 남기고 상태는 바꾸지 않음</li>
     * </ul>
     *
     * @param event 확보 성공 응답 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}
     */
    @Transactional
    public ConfirmResult confirm(MarginReserved event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, subscriptionId={}", event.eventId(), event.subscriptionId());
            return ConfirmResult.DUPLICATE_EVENT_IGNORED;
        }
        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + event.subscriptionId()));

        return switch (subscription.getStatus()) {
            case PENDING -> confirmPending(subscription, event);
            case CONFIRMED, CANCEL_REQUESTED, ALLOCATED, REFUNDED -> {
                log.info("이미 확정된 청약의 중복 응답이라 무시함. subscriptionId={}, status={}", subscription.getId(), subscription.getStatus());
                yield ConfirmResult.ALREADY_CONFIRMED_IGNORED;
            }
            case COMPENSATING, CANCELLED, REJECTED -> {
                log.error("정상적으로는 오지 않는 증거금 확보 성공 응답을 받음. 상태를 바꾸지 않음. subscriptionId={}, status={}, marginId={}",
                        subscription.getId(), subscription.getStatus(), event.marginId());
                yield ConfirmResult.UNEXPECTED_STATE_IGNORED;
            }
        };
    }

    private ConfirmResult confirmPending(Subscription subscription, MarginReserved event) {
        if (event.amount() != subscription.getMarginAmount()) {
            log.warn("확보 금액이 청약의 증거금과 다름. subscriptionId={}, marginAmount={}, reservedAmount={}",
                    subscription.getId(), subscription.getMarginAmount(), event.amount());
        }
        subscription.confirm(event.marginId(), event.reservedAt());
        log.info("청약을 확정함. subscriptionId={}, marginId={}", subscription.getId(), event.marginId());
        return ConfirmResult.CONFIRMED;
    }
}
