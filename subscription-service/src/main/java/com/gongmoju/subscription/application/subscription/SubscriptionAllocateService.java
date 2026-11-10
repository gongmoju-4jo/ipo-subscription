package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.subscription.AllocationResult;
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
public class SubscriptionAllocateService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-allocation-result";
    public static final String TOPIC = "allocation.result";

    private final ProcessedEventRepository processedEventRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 배정 결과를 처리함 (이벤트 명세 {@code allocation.result} 절). 처리 기록, 청약 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * <p>
     * 먼저 이벤트의 {@code ipoId}, {@code accountId}가 저장된 청약과 같은지 검증하고, 받은 때의 청약 상태에 따라 처리함.
     * <ul>
     *   <li>{@code CONFIRMED}: 배정 수량이 청약 수량 이하이고 배정 대금이 배정 수량 × 공모가와 같으면 {@code ALLOCATED}로 전환함</li>
     *   <li>{@code ALLOCATED}, {@code REFUNDED}: 저장한 배정 결과와 같으면 중복으로 보고 정상 종료함. 다르면 덮어쓰지 않고 예외를 던짐.
     *       {@code REFUNDED}는 {@code margin.settled}가 먼저 와서 배정 결과를 저장한 경우임 (결정 9)</li>
     *   <li>그 외({@code PENDING}, {@code REJECTED}, {@code COMPENSATING}, {@code CANCEL_REQUESTED}, {@code CANCELLED}): 정상적으로는 오지 않는 결과이므로
     *       상태를 바꾸지 않고 예외를 던짐. 마감은 진행 중 청약이 0건일 때만 되고 배정 대상은 {@code CONFIRMED}뿐이라, 오면 배정 서비스나 데이터 문제임.
     *       {@code PENDING}, {@code COMPENSATING}, {@code CANCEL_REQUESTED}는 증거금이 아직 {@code PENDING}일 수 있어 계좌 서비스는 같은 메시지로 정산할 수 있음</li>
     * </ul>
     * 검증 실패는 다시 처리해도 결과가 같으므로 재시도 없이 DLT로 보냄 (이벤트 명세 3-3). 처리 기록도 함께 롤백되므로, 원인을 고친 뒤 DLT 메시지를 재발행하면 다시 처리됨 (이벤트 명세 3-5).
     *
     * @param event 배정 결과 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}, {@code ipoId}나 {@code accountId} 불일치,
     *                               배정 수량이 청약 수량보다 큼, 배정 대금이 배정 수량 × 공모가와 다름, 이미 저장한 배정 결과와 다름,
     *                               {@code CONFIRMED}, {@code ALLOCATED}, {@code REFUNDED} 외 상태인 경우
     */
    @Transactional
    public AllocateResult allocate(AllocationResult event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, subscriptionId={}", event.eventId(), event.subscriptionId());
            return AllocateResult.DUPLICATE_EVENT_IGNORED;
        }
        Subscription subscription = subscriptionRepository.findById(event.subscriptionId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + event.subscriptionId()));
        checkSameSubscription(subscription, event);

        return switch (subscription.getStatus()) {
            case CONFIRMED -> allocateConfirmed(subscription, event);
            case ALLOCATED, REFUNDED -> ignoreSameAllocation(subscription, event);
            case PENDING, REJECTED, COMPENSATING, CANCEL_REQUESTED, CANCELLED -> {
                // 처리 기록을 남기고 끝내면 원인을 고친 뒤 재발행해도 중복으로 무시되므로, 예외로 롤백하고 DLT에 메시지를 남김
                log.error("정상적으로는 오지 않는 배정 결과를 받음. 상태를 바꾸지 않고 DLT로 보냄. subscriptionId={}, status={}, allocatedQuantity={}, allocatedAmount={}",
                        subscription.getId(), subscription.getStatus(), event.allocatedQuantity(), event.allocatedAmount());
                throw new InvalidEventException("배정 결과를 받을 수 없는 청약 상태입니다: subscriptionId=" + subscription.getId()
                        + ", status=" + subscription.getStatus());
            }
        };
    }

    // 이벤트의 계좌 ID나 공모주 ID만으로 처리하지 않고, 저장된 청약과 같은지 확인함 (이벤트 명세)
    private void checkSameSubscription(Subscription subscription, AllocationResult event) {
        if (!subscription.getIpo().getId().equals(event.ipoId()) || !subscription.getAccountId().equals(event.accountId())) {
            log.error("배정 결과의 공모주나 계좌가 청약과 다름. DLT로 보냄. subscriptionId={}, expectedIpoId={}, ipoId={}, expectedAccountId={}, accountId={}",
                    subscription.getId(), subscription.getIpo().getId(), event.ipoId(), subscription.getAccountId(), event.accountId());
            throw new InvalidEventException("배정 결과의 ipoId 또는 accountId가 청약과 다릅니다: subscriptionId=" + subscription.getId());
        }
    }

    private AllocateResult allocateConfirmed(Subscription subscription, AllocationResult event) {
        long offerPrice = subscription.getIpo().getOfferPrice();
        if (event.allocatedQuantity() > subscription.getRequestedQuantity()
                || event.allocatedAmount() != Math.multiplyExact(event.allocatedQuantity(), offerPrice)) {
            // 정상 흐름에서는 생길 수 없음 (배정 서비스 문제). 틀린 값을 저장하면 정산 검증(margin.settled)까지 어긋나므로 저장하지 않고 DLT로 보냄
            log.error("배정 결과가 청약 수량이나 공모가와 맞지 않음. DLT로 보냄. subscriptionId={}, requestedQuantity={}, offerPrice={}, allocatedQuantity={}, allocatedAmount={}",
                    subscription.getId(), subscription.getRequestedQuantity(), offerPrice, event.allocatedQuantity(), event.allocatedAmount());
            throw new InvalidEventException("배정 결과가 청약 수량이나 공모가와 맞지 않습니다: subscriptionId=" + subscription.getId()
                    + ", allocatedQuantity=" + event.allocatedQuantity() + ", allocatedAmount=" + event.allocatedAmount());
        }
        subscription.allocate(event.allocatedQuantity(), event.allocatedAmount());
        log.info("청약을 배정 완료함. subscriptionId={}, allocatedQuantity={}, allocatedAmount={}",
                subscription.getId(), event.allocatedQuantity(), event.allocatedAmount());
        return AllocateResult.ALLOCATED;
    }

    private AllocateResult ignoreSameAllocation(Subscription subscription, AllocationResult event) {
        if (!subscription.hasSameAllocation(event.allocatedQuantity(), event.allocatedAmount())) {
            // 저장한 값을 덮어쓰지 않음. 운영자가 계좌 서비스의 정산 값과 대조해 보정함 (이벤트 명세 margin.settled 절, 3-5)
            log.error("이미 저장한 배정 결과와 다름. 덮어쓰지 않고 DLT로 보냄. subscriptionId={}, status={}, storedQuantity={}, storedAmount={}, allocatedQuantity={}, allocatedAmount={}",
                    subscription.getId(), subscription.getStatus(), subscription.getAllocatedQuantity(), subscription.getAllocatedAmount(),
                    event.allocatedQuantity(), event.allocatedAmount());
            throw new InvalidEventException("이미 저장한 배정 결과와 다릅니다: subscriptionId=" + subscription.getId());
        }
        log.info("이미 배정 결과를 저장한 청약의 중복 결과라 무시함. subscriptionId={}, status={}", subscription.getId(), subscription.getStatus());
        return AllocateResult.ALREADY_ALLOCATED_IGNORED;
    }
}
