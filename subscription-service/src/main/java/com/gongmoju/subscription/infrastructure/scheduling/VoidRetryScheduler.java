package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.subscription.SubscriptionVoidRetryService;
import com.gongmoju.subscription.application.subscription.VoidRetryResult;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 응답 제한 시간을 넘긴 COMPENSATING, CANCEL_REQUESTED 청약을 주기적으로 조회해 증거금 반환을 재요청 처리 (이벤트 명세 3-5, 상태 전이도의 멈춘 SAGA 정리).
 * 마지막 요청 시각에서 응답 시간이 지나고 최대 횟수에 도달하지 않은 청약을 대상으로 진행. 미발행 청약은 조회에서 제외.
 * 최대 횟수에 도달한 청약은 상태를 변경하지 않고(자동 종료, 강제 마감 없음, 결정 14) 알림 간격마다 ERROR 로그만 기록.(아직 알림으로 이어지지 않음)
 * 각 청약을 개별 처리하며, 1 건의 실패가 나머지 처리를 중단하지 않음. 낙관적 락 충돌은 건너뛰고 다음 주기에 재판단함.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "saga.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
class VoidRetryScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionVoidRetryService voidRetryService;
    private final PendingTimeoutProperties properties;
    private final Duration alertInterval;
    private Instant lastAlertAt;    // 마지막으로 알림 로그를 남긴 시각 (메모리, 마감 스케줄러와 같은 방식. 재시작 시 다시 남김)

    VoidRetryScheduler(SubscriptionRepository subscriptionRepository,
                       SubscriptionVoidRetryService voidRetryService,
                       PendingTimeoutProperties properties,
                       @Value("${saga.timeout.alert-interval:PT5M}") Duration alertInterval) {
        this.subscriptionRepository = subscriptionRepository;
        this.voidRetryService = voidRetryService;
        this.properties = properties;
        this.alertInterval = alertInterval;
    }

    /**
     * 이전 실행 종료 후 실행 간격(interval) 지나면 재실행 (실행이 겹치지 않음)
     */
    @Scheduled(fixedDelayString = "${saga.timeout.interval:PT5S}")
    void retryStaleVoid() {
        retryStaleVoid(Instant.now());
    }

    /**
     * 지정한 시각을 기준으로 반환 재요청 대상을 조회해 건별로 처리, 최대 횟수에 이른 청약은 알림 간격마다 ERROR 로그 기록
     */
    void retryStaleVoid(Instant now) {
        Instant lastRequestedBefore = now.minus(properties.responseTimeout());
        retry(lastRequestedBefore);
        alertExhausted(now, lastRequestedBefore);
    }

    private void retry(Instant lastRequestedBefore) {
        List<UUID> targets = subscriptionRepository.findStaleVoidIds(lastRequestedBefore, properties.maxAttempts(), properties.batchSize());
        if (targets.isEmpty()) {
            return;
        }

        Map<VoidRetryResult.Outcome, Integer> counts = new EnumMap<>(VoidRetryResult.Outcome.class);
        int skipped = 0;
        int failed = 0;
        for (UUID subscriptionId : targets) {
            try {
                VoidRetryResult result = voidRetryService.handle(subscriptionId, lastRequestedBefore, properties.maxAttempts());
                counts.merge(result.outcome(), 1, Integer::sum);
            } catch (OptimisticLockingFailureException e) {
                // 다른 Consumer, 인스턴스가 먼저 청약을 변경. 이미 처리됐거나 다음 주기에 재판단
                skipped++;
                log.info("청약이 동시에 변경되어 반환 재요청을 건너뜀. 다음 주기에 다시 판단함. subscriptionId={}", subscriptionId);
            } catch (RuntimeException e) {
                failed++;
                log.error("반환 재요청 처리 중 오류. 다음 주기에 다시 시도함. subscriptionId={}", subscriptionId, e);
            }
        }
        log.info("반환 재요청 처리 완료. 대상 {}건, 결과={}, 동시 변경으로 건너뜀 {}건, 오류 {}건", targets.size(), counts, skipped, failed);
    }

    // 최대 횟수에 이른 청약은 재요청 대상 조회와 따로 조회 (계속 남아 재요청 대상의 건수 제한을 채우지 않게 함). 알림 간격마다 한 번 확인
    private void alertExhausted(Instant now, Instant lastRequestedBefore) {
        if (lastAlertAt != null && now.isBefore(lastAlertAt.plus(alertInterval))) {
            return;
        }
        try {
            List<UUID> exhausted = subscriptionRepository.findExhaustedVoidIds(lastRequestedBefore, properties.maxAttempts(), properties.batchSize());
            if (exhausted.isEmpty()) {
                return;
            }
            for (UUID subscriptionId : exhausted) {
                VoidRetryResult result = voidRetryService.handle(subscriptionId, lastRequestedBefore, properties.maxAttempts());
                if (result.outcome() == VoidRetryResult.Outcome.MAX_ATTEMPTS_REACHED) {
                    log.error("반환 재요청이 최대 횟수에 도달했지만 계좌 응답이 없음. 자동으로 종료하거나 마감하지 않으니 운영자 확인 필요 "
                                    + "(margin.void-requested.DLT, margin.voided.DLT, 계좌의 margin 상태). subscriptionId={}, status={}, reason={}, attempts={}, lastRequestedAt={}",
                            subscriptionId, result.status(), result.cancelReason(), result.attempts(), result.lastRequestedAt());
                }
            }
            lastAlertAt = now;
        } catch (RuntimeException e) {
            log.error("반환 재요청 최대 횟수 도달 알림 확인 중 오류. 다음 주기에 다시 시도함.", e);
        }
    }
}
