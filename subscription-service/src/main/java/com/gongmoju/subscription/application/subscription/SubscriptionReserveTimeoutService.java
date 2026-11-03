package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 증거금 인출 응답이 지연된 청약을 재요청하거나 시간 초과 보상으로 전환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionReserveTimeoutService {

    private final SubscriptionRepository subscriptionRepository;
    private final MarginReserveRequestRecorder marginReserveRequestRecorder;
    private final SubscriptionCompensationService compensationService;

    /**
     * 응답이 오지 않은 {@code PENDING} 청약 1건을 처리함 (이벤트 명세 3-5, 4장 위험 4).
     * 청약 1건마다 트랜잭션을 분리해야 실패가 다른 건에 번지지 않음. 청약 1건 당 메서드 1번으로 처리하고 호출하는 쪽(스케줄러)이 건마다 호출함.
     * 대상 조회와 처리 사이에 상태가 변경되었을 수 있으므로 처리 시점에 조건(상태, 요청 시각)을 재확인함.
     *
     * <ul>
     *   <li>{@code PENDING}이 아님: 아무것도 변경하지 않음</li>
     *   <li>마지막 요청 시각이 {@code lastRequestedBefore} 이후: 이미 재요청되었으므로 변경하지 않음</li>
     *   <li>요청 횟수가 최대 횟수 미만: 증거금 인출 요청을 Outbox에 다시 기록하고 요청 횟수를 증가. 같은 {@code subscriptionId}라서 계좌가 중복 인출하지 않음</li>
     *   <li>요청 횟수가 최대 횟수 이상: {@code COMPENSATING(TIMEOUT)}으로 전환하고 증거금 반환 요청을 기록.</li>
     * </ul>
     *
     * 낙관적 락({@code version}) 충돌은 커밋 때 예외로 올라오며, 호출하는 쪽이 건너뛰고 다음 주기에 다시 판단함.
     *
     * @param subscriptionId      처리할 청약 ID
     * @param lastRequestedBefore 마지막 요청 시각이 이 시각보다 이른 청약만 처리
     * @param maxAttempts         최초 요청을 포함한 최대 인출 요청 횟수
     * @return                    재요청, 보상 시작 또는 건너뛰기 결과
     * @throws IllegalStateException 존재하지 않는 {@code subscriptionId} (대상 조회 결과로만 호출하므로 정상적으로는 일어나지 않음)
     */
    @Transactional
    public ReserveTimeoutResult handle(UUID subscriptionId, Instant lastRequestedBefore, int maxAttempts) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("응답 시간 초과 처리 대상 청약이 없습니다. subscriptionId=" + subscriptionId));

        if (subscription.getStatus() != SubscriptionStatus.PENDING) {
            log.info("PENDING이 아니라서 응답 시간 초과 처리를 건너뜀. subscriptionId={}, status={}", subscriptionId, subscription.getStatus());
            return ReserveTimeoutResult.NOT_PENDING_IGNORED;
        }
        Instant lastRequestedAt = subscription.getLastRequestedAt();
        if (lastRequestedAt == null || !lastRequestedAt.isBefore(lastRequestedBefore)) {
            log.info("이미 다시 요청되어 응답 시간 초과 처리를 건너뜀. subscriptionId={}, lastRequestedAt={}", subscriptionId, lastRequestedAt);
            return ReserveTimeoutResult.NOT_TIMED_OUT_IGNORED;
        }

        if (subscription.hasReachedMaxAttempts(maxAttempts)) {
            compensationService.startTimeoutCompensation(subscriptionId);
            return ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED;
        }

        subscription.retryReserve();
        marginReserveRequestRecorder.record(subscription);
        log.warn("계좌 응답이 없어서 증거금 인출을 다시 요청함. subscriptionId={}, attempts={}, 이전 마지막 요청 시각={}",
                subscriptionId, subscription.getAttempts(), lastRequestedAt);
        return ReserveTimeoutResult.RETRIED;
    }
}
