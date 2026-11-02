package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionCompensationService {

    private final SubscriptionRepository subscriptionRepository;
    private final MarginVoidRequestRecorder marginVoidRequestRecorder;

    /**
     * 증거금은 인출됐는데 청약 확정에 실패했을 때 보상을 시작함 (이벤트 명세 3-5 {@code margin.reserved.DLT} 행).
     * {@code PENDING}을 {@code COMPENSATING(CONFIRM_FAILED)}로 바꾸고 증거금 해제 요청({@code margin.void-requested})을 Outbox에 기록하며, 둘은 한 트랜잭션임.
     * 전환만 하고 요청을 기록하지 않으면 계좌에 요청이 가지 않아 {@code COMPENSATING}에 멈추므로 하나라도 실패하면 모두 롤백됨.
     * <p>
     * {@code PENDING}이 아니면(이미 확정, 보상 중, 취소, 거절 등) 아무것도 바꾸지 않고 로그만 남김. 같은 청약에 해제 요청이 두 번 기록되지 않음.
     * 호출하는 쪽의 트랜잭션에 참여하지 않도록 트랜잭션이 없는 곳(재시도를 모두 소진한 뒤의 복구 핸들러)에서 호출함.
     *
     * @param subscriptionId 확정에 실패한 청약 ID
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code subscriptionId}
     */
    @Transactional
    public CompensationResult startConfirmFailedCompensation(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 subscriptionId입니다: " + subscriptionId));

        if (subscription.getStatus() != SubscriptionStatus.PENDING) {
            log.warn("PENDING이 아니라서 보상을 시작하지 않음. subscriptionId={}, status={}", subscriptionId, subscription.getStatus());
            return CompensationResult.NOT_PENDING_IGNORED;
        }
        subscription.startCompensation(CancelReason.CONFIRM_FAILED);
        marginVoidRequestRecorder.record(subscription);
        log.error("청약 확정에 실패해서 보상을 시작함. 증거금 해제를 요청함. subscriptionId={}, reason={}", subscriptionId, CancelReason.CONFIRM_FAILED);
        return CompensationResult.STARTED;
    }
}
