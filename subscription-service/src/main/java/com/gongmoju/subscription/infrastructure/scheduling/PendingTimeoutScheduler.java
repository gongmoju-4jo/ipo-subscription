package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.subscription.ReserveTimeoutResult;
import com.gongmoju.subscription.application.subscription.SubscriptionReserveTimeoutService;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 응답 제한 시간을 넘긴 PENDING 청약을 주기적으로 조회해 증거금 인출을 재요청하거나 시간 초과 보상을 시작함 (이벤트 명세 3-5, 4장 위험 4)
 * 마지막 요청 시각에서 응답 시간(30초)이 지난 PENDING 청약은 인출 요청을 재요청하고, 최대 횟수에 도달하면 COMPENSATING(TIMEOUT)으로 보상함
 * 미발행 {@code margin.reserve-requested} Outbox 항목이 있는 청약은 조회에서 제외함
 * 각 청약을 개별 처리하며, 1 건의 실패가 나머지 처리를 중단하지 않음. 낙관적 락 충돌은 건너뛰고 다음 주기에 재판단함.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "saga.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
class PendingTimeoutScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionReserveTimeoutService timeoutService;
    private final PendingTimeoutProperties properties;

    PendingTimeoutScheduler(SubscriptionRepository subscriptionRepository,
                            SubscriptionReserveTimeoutService timeoutService,
                            PendingTimeoutProperties properties) {
        this.subscriptionRepository = subscriptionRepository;
        this.timeoutService = timeoutService;
        this.properties = properties;
    }

    /**
     * 이전 실행 종료 후 실행 간격(interval) 지나면 재실행 (실행이 겹치지 않음)
     */
    @Scheduled(fixedDelayString = "${saga.timeout.interval:PT5S}")
    void handleTimedOutPending() {
        handleTimedOutPending(Instant.now());
    }

    /**
     * 지정한 시각을 기준으로 시간 초과 처리 대상 청약을 조회하고 건별로 처리
     */
    void handleTimedOutPending(Instant now) {
        Instant lastRequestedBefore = now.minus(properties.responseTimeout());
        List<UUID> targets = subscriptionRepository.findStalePendingIds(lastRequestedBefore, properties.batchSize());
        if (targets.isEmpty()) {
            return;
        }

        Map<ReserveTimeoutResult, Integer> counts = new EnumMap<>(ReserveTimeoutResult.class);
        int skipped = 0;
        int failed = 0;
        for (UUID subscriptionId : targets) {
            try {
                ReserveTimeoutResult result = timeoutService.handle(subscriptionId, lastRequestedBefore, properties.maxAttempts());
                counts.merge(result, 1, Integer::sum);
            } catch (OptimisticLockingFailureException e) {
                // 다른 Consumer, 인스턴스가 먼저 청약을 변경. 이미 처리됐거나 다음 주기에 재판단
                skipped++;
                log.info("청약이 동시에 변경되어 응답 시간 초과 처리를 건너뜀. 다음 주기에 다시 판단함. subscriptionId={}", subscriptionId);
            } catch (RuntimeException e) {
                failed++;
                log.error("응답 시간 초과 처리 중 오류. 다음 주기에 다시 시도함. subscriptionId={}", subscriptionId, e);
            }
        }
        log.info("응답 시간 초과 처리 완료. 대상 {}건, 결과={}, 동시 변경으로 건너뜀 {}건, 오류 {}건", targets.size(), counts, skipped, failed);
    }
}
