package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.subscription.ReserveTimeoutResult;
import com.gongmoju.subscription.application.subscription.SubscriptionReserveTimeoutService;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * 인스턴스가 여러 개면 ShedLock({@code @SchedulerLock})으로 한 번에 한 인스턴스만 실행
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
     * lockAtMostFor는 실행 중 인스턴스가 죽었을 때 풀리기까지의 시간(가장 긴 실행보다 넉넉히), lockAtLeastFor는 interval보다 조금 짧게 둠.
     * lockAtLeastFor가 없으면 한 인스턴스가 끝나자마자 다른 인스턴스가 실행해 인스턴스 수만큼 실행. interval이 설정값이라 둘 다 설정 값으로 둠
     * lockAtMostFor는 lockAtLeastFor와 같거나 길어야 함 (짧으면 ShedLock이 락 설정을 만들 때 예외를 던져 실행되지 않음).
     */
    @Scheduled(fixedDelayString = "${saga.timeout.interval:PT5S}")
    @SchedulerLock(name = "PendingTimeoutScheduler.handleTimedOutPending",
            lockAtMostFor = "${saga.timeout.lock-at-most-for:PT1M}", lockAtLeastFor = "${saga.timeout.lock-at-least-for:PT4S}")
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
