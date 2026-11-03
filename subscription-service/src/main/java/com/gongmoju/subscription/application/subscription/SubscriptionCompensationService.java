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

import java.time.Instant;
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

    /**
     * 계좌 서비스의 응답이 오지 않은 채 재요청 최대 횟수에 도달했을 때 시간 초과 보상을 시작함 (이벤트 명세 3-5 {@code margin.reserve-requested.DLT} 행).
     * {@code PENDING}을 {@code COMPENSATING(TIMEOUT)}으로 바꾸고 증거금 반환 요청({@code margin.void-requested})을 Outbox에 기록함(동일 트랜잭션).
     * 증거금 인출 여부를 확인 전에 발행하므로 {@code margin_id}를 설정하지 않음.
     * 청약 상태가 {@code PENDING}이 아니면 상태를 변경하지 않고 로그만 기록. 호출자(응답 시간 초과 처리)의 트랜잭션에 참여.
     *
     * @param subscriptionId 시간 초과된 청약 ID
     * @return 처리 결과
     * @throws IllegalStateException 존재하지 않는 {@code subscriptionId} (대상 조회 결과로만 호출하므로 정상적으로는 일어나지 않음)
     */
    @Transactional
    public CompensationResult startTimeoutCompensation(UUID subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("시간 초과 보상 대상 청약이 없습니다. subscriptionId=" + subscriptionId));

        if (subscription.getStatus() != SubscriptionStatus.PENDING) {
            log.warn("PENDING이 아니라서 시간 초과 보상을 시작하지 않음. subscriptionId={}, status={}", subscriptionId, subscription.getStatus());
            return CompensationResult.NOT_PENDING_IGNORED;
        }
        // 전환하면 attempts가 반환 요청 횟수(1)로 바뀌므로 알림 로그에 남길 값은 전환 전에 읽음
        int attempts = subscription.getAttempts();
        Instant lastRequestedAt = subscription.getLastRequestedAt();
        subscription.startCompensation(CancelReason.TIMEOUT);
        marginVoidRequestRecorder.record(subscription);
        log.error("계좌 응답이 없어서 시간 초과로 보상을 시작함. 증거금 반환을 요청함. subscriptionId={}, reason={}, 이전 attempts={}, 마지막 인출 요청 시각={}",
                subscriptionId, CancelReason.TIMEOUT, attempts, lastRequestedAt);
        return CompensationResult.STARTED;
    }
}
