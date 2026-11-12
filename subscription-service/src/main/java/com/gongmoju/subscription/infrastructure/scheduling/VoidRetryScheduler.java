package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.subscription.SubscriptionVoidRetryService;
import com.gongmoju.subscription.application.subscription.VoidRetryResult;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
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
import java.util.OptionalLong;
import java.util.UUID;

/**
 * 응답이 오지 않는 COMPENSATING, CANCEL_REQUESTED 청약을 주기적으로 조회해 증거금 반환을 재요청 처리 (이벤트 명세 3-5, 상태 전이도의 멈춘 SAGA 정리).
 * 마지막 요청 시각에서 대기 시간(요청 횟수가 늘수록 2배, 상한까지)이 지난 청약을 대상으로 진행. 최대 횟수는 없음 (응답이 올 때까지 계속 재요청). 미발행 청약은 조회에서 제외.
 * 요청 횟수가 알림 기준({@code saga.void-retry.alert-attempts}) 이상인데 응답이 없으면 상태를 변경하지 않고(자동 종료, 강제 마감 없음, 결정 14)
 * 알림 간격마다 ERROR 로그만 기록. 재요청은 계속함.
 * 각 청약을 개별 처리하며, 1 건의 실패가 나머지 처리를 중단하지 않음. 낙관적 락 충돌은 건너뛰고 다음 주기에 재판단함.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "saga.timeout", name = "enabled", havingValue = "true", matchIfMissing = true)
class VoidRetryScheduler {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionVoidRetryService voidRetryService;
    private final VoidRetryProperties properties;
    private final Duration alertInterval;
    private Instant lastAlertAt;    // 마지막으로 알림 로그를 남긴 시각 (메모리, 마감 스케줄러와 같은 방식. 재시작 시 다시 남김)

    VoidRetryScheduler(SubscriptionRepository subscriptionRepository,
                       SubscriptionVoidRetryService voidRetryService,
                       VoidRetryProperties properties,
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
     * 지정한 시각을 기준으로 반환 재요청 대상을 조회해 건별로 처리, 오래 걸리는 반환은 알림 간격마다 ERROR 로그 기록
     */
    void retryStaleVoid(Instant now) {
        retry(now);
        alertLongVoiding(now);
    }

    private void retry(Instant now) {
        VoidRetryBackoff backoff = properties.backoff();
        List<UUID> targets = subscriptionRepository.findStaleVoidIds(now, backoff, properties.batchSize());
        if (targets.isEmpty()) {
            return;
        }

        Map<VoidRetryResult.Outcome, Integer> counts = new EnumMap<>(VoidRetryResult.Outcome.class);
        int skipped = 0;
        int failed = 0;
        for (UUID subscriptionId : targets) {
            try {
                VoidRetryResult result = voidRetryService.handle(subscriptionId, now, backoff);
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

    // 오래 걸리는 반환(요청 횟수가 알림 기준 이상이고 응답이 없는 청약)은 재요청 대상 조회와 따로, 읽기 전용으로 조회함. 알림 간격마다 한 번 확인.
    // 재요청은 계속하므로 이 청약들은 재요청 대상 조회에도 계속 나타남 (알림은 재요청을 막지 않음).
    // 상세 로그는 오래된 순 앞쪽 batch 건수까지만 남기고(이 청약들이 풀리지 않으면 다음 주기에도 같은 청약이 앞쪽임),
    // 총 건수와 상세에서 빠진 건수를 요약 로그로 남겨서 누락이 보이게 함. 빠진 청약은 DB에서 조회해야 함.
    // 알림 조회는 청약을 바꾸지 않음 (재요청 서비스(handle)를 거치지 않음).
    private void alertLongVoiding(Instant now) {
        if (lastAlertAt != null && now.isBefore(lastAlertAt.plus(alertInterval))) {
            return;
        }
        try {
            Duration minAge = properties.initialInterval();
            List<Subscription> longVoiding = subscriptionRepository.findLongVoiding(now, properties.alertAttempts(), minAge, properties.batchSize());
            if (longVoiding.isEmpty()) {
                return;
            }
            for (Subscription subscription : longVoiding) {
                log.error("반환 응답이 {}번째 요청까지 오지 않음. 재요청은 계속하지만 오래 걸리니 운영자 확인 필요 "
                                + "(margin.void-requested.DLT, margin.voided.DLT, 계좌의 margin 상태). subscriptionId={}, status={}, reason={}, attempts={}, lastRequestedAt={}",
                        subscription.getAttempts(), subscription.getId(), subscription.getStatus(), subscription.getCancelReason(),
                        subscription.getAttempts(), subscription.getLastRequestedAt());
            }
            logSummary(now, minAge, longVoiding.size());
            lastAlertAt = now;
        } catch (RuntimeException e) {
            log.error("반환 지연 알림 확인 중 오류. 다음 주기에 다시 시도함.", e);
        }
    }

    // 요약 로그. 총 건수 조회가 실패하면 총 건수와 빠진 건수를 확정해서 기록하지 않고 확인 불가로 표시함
    // (상세 건수를 총 건수로 대신하면 실제 대상이 더 있어도 "빠진 0건"으로 보임)
    private void logSummary(Instant now, Duration minAge, int logged) {
        OptionalLong total = countLongVoiding(now, minAge);
        String lookup = "status IN ('COMPENSATING', 'CANCEL_REQUESTED') AND attempts >= " + properties.alertAttempts()
                + " AND last_requested_at < now() - " + minAge + " (오래된 순 뒤쪽)";
        if (total.isPresent()) {
            long all = Math.max(total.getAsLong(), logged);
            log.error("반환 지연 청약 요약. 총 {}건, 상세 로그 {}건, 상세에서 빠진 {}건. 빠진 청약은 DB에서 조회: {}",
                    all, logged, all - logged, lookup);
        } else {
            log.error("반환 지연 청약 요약. 총 건수 조회에 실패해 총 건수와 상세에서 빠진 건수는 확인 불가. 상세 로그 {}건. "
                    + "상세에서 빠진 청약이 있을 수 있으니 DB에서 조회: {}", logged, lookup);
        }
    }

    // 총 건수 조회가 실패해도 상세 로그는 이미 남았으므로 알림을 막지 않음. 실패하면 값을 만들지 않고 빈 값을 돌려줌
    private OptionalLong countLongVoiding(Instant now, Duration minAge) {
        try {
            return OptionalLong.of(subscriptionRepository.countLongVoiding(now, properties.alertAttempts(), minAge));
        } catch (RuntimeException e) {
            log.warn("반환 지연 청약의 총 건수를 조회하지 못함.", e);
            return OptionalLong.empty();
        }
    }
}
