package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionAcceptService {

    private final SubscriptionRequestValidator validator;
    private final SubscriptionRepository subscriptionRepository;
    private final MarginReserveRequestRecorder marginReserveRequestRecorder;

    /**
     * 접수된 청약 요청을 처리함 (이벤트 명세 2-1). 검증, 저장, 증거금 확보 요청 기록을 한 트랜잭션으로 처리함.
     * <p>
     * 동시 요청으로 {@code uk_subscription_active} 위반이 나면 예외를 그대로 전파하여 롤백함.
     * 호출한 쪽(Consumer)이 재시도하고, 재시도에서 기존 청약을 찾아 {@code REJECTED}로 저장함.
     *
     * @param event 접수 내용 (검증 1을 통과한 값)
     * @return 저장 결과
     * @throws InvalidEventException 검증 2, 3 실패, 같은 {@code requestId}인데 요청 내용이 다름, 증거금 계산이 {@code long} 범위를 넘음
     */
    @Transactional
    public AcceptResult accept(SubscriptionRequested event) {
        Ipo ipo = validator.validate(event);

        // 검증 4
        Optional<Subscription> existing = subscriptionRepository.findByRequestId(event.requestId());
        if (existing.isPresent()) {
            return ignoreIfSameRequest(existing.get(), event);
        }

        long marginAmount = calculateMarginAmount(event, ipo);
        boolean alreadySubscribed = subscriptionRepository.existsActiveByIpoIdAndUserId(event.ipoId(), event.userId());
        Subscription subscription = alreadySubscribed ? rejectedOf(event, ipo, marginAmount) : pendingOf(event, ipo, marginAmount);

        if (!subscriptionRepository.insertIfAbsent(subscription)) {
            // 같은 requestId의 요청이 동시에 먼저 저장됨. 기존 행을 읽어 검증 5로 이어감
            Subscription saved = subscriptionRepository.findByRequestId(event.requestId())
                    .orElseThrow(() -> new IllegalStateException("저장이 충돌했지만 기존 청약을 찾을 수 없습니다. requestId=" + event.requestId()));
            return ignoreIfSameRequest(saved, event);
        }
        if (alreadySubscribed) {
            log.info("이중청약으로 거절함. subscriptionId={}, userId={}, ipoId={}", event.subscriptionId(), event.userId(), event.ipoId());
            return AcceptResult.REJECTED_ALREADY_SUBSCRIBED;
        }
        marginReserveRequestRecorder.record(subscription);
        return AcceptResult.ACCEPTED;
    }

    // 검증 5
    private AcceptResult ignoreIfSameRequest(Subscription existing, SubscriptionRequested event) {
        if (!existing.isSameRequest(event)) {
            throw new InvalidEventException("같은 requestId인데 요청 내용이 다릅니다: " + event.requestId());
        }
        log.info("같은 내용의 재전송이라 무시함. requestId={}, subscriptionId={}", event.requestId(), event.subscriptionId());
        return AcceptResult.DUPLICATE_IGNORED;
    }

    // 증거금률 100%라 청약 금액과 같음 (검증 3에서 증거금률 확인). long 범위를 넘으면 재처리해도 같은 결과이므로 재시도 없는 예외로 처리함
    private long calculateMarginAmount(SubscriptionRequested event, Ipo ipo) {
        try {
            return Math.multiplyExact(event.requestedQuantity(), ipo.getOfferPrice());
        } catch (ArithmeticException e) {
            throw new InvalidEventException("증거금이 long 범위를 넘습니다: 수량=" + event.requestedQuantity() + ", 공모가=" + ipo.getOfferPrice());
        }
    }

    private Subscription pendingOf(SubscriptionRequested event, Ipo ipo, long marginAmount) {
        return Subscription.accept(event.subscriptionId(), event.requestId(), ipo, event.userId(), event.accountId(),
                event.requestedQuantity(), marginAmount, event.receivedAt());
    }

    private Subscription rejectedOf(SubscriptionRequested event, Ipo ipo, long marginAmount) {
        return Subscription.reject(event.subscriptionId(), event.requestId(), ipo, event.userId(), event.accountId(),
                event.requestedQuantity(), marginAmount, event.receivedAt(), RejectReason.ALREADY_SUBSCRIBED);
    }
}
