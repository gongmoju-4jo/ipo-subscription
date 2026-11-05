package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
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
public class SubscriptionRefundService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-margin-settled";
    public static final String TOPIC = "margin.settled";

    private final ProcessedEventRepository processedEventRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 정산 결과를 처리함 (이벤트 명세 {@code margin.settled} 절). 처리 기록, 청약 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * <p>
     * 받은 때의 청약 상태에 따라 처리함.
     * <ul>
     *   <li>{@code ALLOCATED}: 정산 금액이 배정 대금과 같고 환불액이 증거금 − 배정 대금과 같으면 {@code REFUNDED}로 전환함</li>
     *   <li>{@code CONFIRMED}: {@code allocation.result}보다 먼저 온 경우임. 무시하면 환불 완료가 유실되므로 한 트랜잭션에서 {@code ALLOCATED}를 거쳐
     *       {@code REFUNDED}로 전환함 (결정 9). 배정 수량은 정산 금액 ÷ 공모가로 계산하고, 나누어떨어지지 않거나 청약 수량보다 크거나
     *       환불액이 증거금 − 정산 금액과 다르면 예외를 던짐. 나중에 오는 {@code allocation.result}는 저장한 값과 같은지만 확인함</li>
     *   <li>{@code REFUNDED}: 저장한 정산 결과와 같으면 중복으로 보고 정상 종료함. 다르면 덮어쓰지 않고 예외를 던짐</li>
     *   <li>그 외({@code PENDING}, {@code REJECTED}, {@code COMPENSATING}, {@code CANCEL_REQUESTED}, {@code CANCELLED}): 배정 대상이 아니라
     *       계좌 서비스가 정산하지 않으므로 예상되는 늦은 응답이 없음. 상태를 바꾸지 않고 예외를 던짐</li>
     * </ul>
     * 검증 실패는 다시 처리해도 결과가 같으므로 재시도 없이 DLT로 보냄 (이벤트 명세 3-3). 처리 기록도 함께 롤백되므로, 원인을 고친 뒤 DLT 메시지를 재발행하면 다시 처리됨 (이벤트 명세 3-5).
     * 금액이 다르면 운영자가 돈이 이미 움직인 계좌 서비스의 정산 값을 기준으로 보정함 (이벤트 명세 {@code margin.settled} 절).
     *
     * @param event 정산 결과 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}, 정산 금액이나 환불액이 저장한 청약과 맞지 않음,
     *                               이미 저장한 정산 결과와 다름, {@code CONFIRMED}, {@code ALLOCATED}, {@code REFUNDED} 외 상태인 경우
     * @throws IllegalStateException {@code ALLOCATED}인데 배정 대금이 없는 경우 (데이터 문제). 재시도 대상이라 재시도를 모두 소진한 뒤 DLT로 보냄
     */
    @Transactional
    public RefundResult refund(MarginSettled event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, subscriptionId={}", event.eventId(), event.subscriptionId());
            return RefundResult.DUPLICATE_EVENT_IGNORED;
        }
        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + event.subscriptionId()));

        return switch (subscription.getStatus()) {
            case ALLOCATED -> refundAllocated(subscription, event);
            case CONFIRMED -> allocateAndRefund(subscription, event);
            case REFUNDED -> ignoreSameRefund(subscription, event);
            case PENDING, REJECTED, COMPENSATING, CANCEL_REQUESTED, CANCELLED -> {
                // 처리 기록을 남기고 끝내면 원인을 고친 뒤 재발행해도 중복으로 무시되므로, 예외로 롤백하고 DLT에 메시지를 남김
                log.error("정상적으로는 오지 않는 정산 결과를 받음. 상태를 바꾸지 않고 DLT로 보냄. subscriptionId={}, status={}, postedAmount={}, refundAmount={}",
                        subscription.getId(), subscription.getStatus(), event.postedAmount(), event.refundAmount());
                throw new InvalidEventException("정산 결과를 받을 수 없는 청약 상태입니다: subscriptionId=" + subscription.getId()
                        + ", status=" + subscription.getStatus());
            }
        };
    }

    private RefundResult refundAllocated(Subscription subscription, MarginSettled event) {
        Long storedAllocatedAmount = subscription.getAllocatedAmount();
        if (storedAllocatedAmount == null) {
            // 정상 흐름에서는 ALLOCATED면 배정 대금이 항상 있음 (데이터 문제). margin.voided의 해제 사유 누락과 같이 재시도 대상 예외로 두고, 재시도 후 DLT로 보냄
            throw new IllegalStateException("ALLOCATED인데 배정 대금이 없는 청약은 환불을 완료할 수 없습니다: subscriptionId=" + subscription.getId());
        }
        long allocatedAmount = storedAllocatedAmount;
        if (event.postedAmount() != allocatedAmount || event.refundAmount() != subscription.getMarginAmount() - allocatedAmount) {
            // 저장한 배정 결과를 덮어쓰지 않음. 운영자가 계좌 서비스의 정산 값과 대조해 보정함 (이벤트 명세 margin.settled 절, 3-5)
            log.error("정산 결과가 배정 대금이나 증거금과 맞지 않음. 덮어쓰지 않고 DLT로 보냄. subscriptionId={}, marginAmount={}, allocatedAmount={}, postedAmount={}, refundAmount={}",
                    subscription.getId(), subscription.getMarginAmount(), allocatedAmount, event.postedAmount(), event.refundAmount());
            throw new InvalidEventException("정산 결과가 배정 대금이나 증거금과 맞지 않습니다: subscriptionId=" + subscription.getId()
                    + ", postedAmount=" + event.postedAmount() + ", refundAmount=" + event.refundAmount());
        }
        subscription.completeRefund(event.refundAmount());
        log.info("청약을 환불 완료함. subscriptionId={}, postedAmount={}, refundAmount={}",
                subscription.getId(), event.postedAmount(), event.refundAmount());
        return RefundResult.REFUNDED;
    }

    // 결정 9: allocation.result보다 먼저 온 정산 결과로 배정 결과까지 저장함
    private RefundResult allocateAndRefund(Subscription subscription, MarginSettled event) {
        long offerPrice = subscription.getIpo().getOfferPrice();
        long allocatedQuantity = event.postedAmount() / offerPrice;
        if (event.postedAmount() % offerPrice != 0
                || allocatedQuantity > subscription.getRequestedQuantity()
                || event.refundAmount() != subscription.getMarginAmount() - event.postedAmount()) {
            // 정상 흐름에서는 생길 수 없음. 틀린 배정 결과를 저장하면 나중에 오는 allocation.result 검증까지 어긋나므로 저장하지 않고 DLT로 보냄
            log.error("배정 결과보다 먼저 온 정산 결과가 공모가, 청약 수량, 증거금과 맞지 않음. DLT로 보냄. subscriptionId={}, offerPrice={}, requestedQuantity={}, marginAmount={}, postedAmount={}, refundAmount={}",
                    subscription.getId(), offerPrice, subscription.getRequestedQuantity(), subscription.getMarginAmount(),
                    event.postedAmount(), event.refundAmount());
            throw new InvalidEventException("정산 결과가 공모가, 청약 수량, 증거금과 맞지 않습니다: subscriptionId=" + subscription.getId()
                    + ", postedAmount=" + event.postedAmount() + ", refundAmount=" + event.refundAmount());
        }
        subscription.allocate(allocatedQuantity, event.postedAmount());
        subscription.completeRefund(event.refundAmount());
        log.info("배정 결과보다 정산 결과가 먼저 와서 배정과 환불을 함께 완료함. subscriptionId={}, allocatedQuantity={}, postedAmount={}, refundAmount={}",
                subscription.getId(), allocatedQuantity, event.postedAmount(), event.refundAmount());
        return RefundResult.ALLOCATED_AND_REFUNDED;
    }

    private RefundResult ignoreSameRefund(Subscription subscription, MarginSettled event) {
        if (!subscription.hasSameRefund(event.postedAmount(), event.refundAmount())) {
            // 저장한 값을 덮어쓰지 않음. 운영자가 계좌 서비스의 정산 값과 대조해 보정함 (이벤트 명세 margin.settled 절, 3-5)
            log.error("이미 저장한 정산 결과와 다름. 덮어쓰지 않고 DLT로 보냄. subscriptionId={}, storedAllocatedAmount={}, storedRefundAmount={}, postedAmount={}, refundAmount={}",
                    subscription.getId(), subscription.getAllocatedAmount(), subscription.getRefundAmount(),
                    event.postedAmount(), event.refundAmount());
            throw new InvalidEventException("이미 저장한 정산 결과와 다릅니다: subscriptionId=" + subscription.getId());
        }
        log.info("이미 환불 완료한 청약의 중복 정산 결과라 무시함. subscriptionId={}", subscription.getId());
        return RefundResult.ALREADY_REFUNDED_IGNORED;
    }
}
