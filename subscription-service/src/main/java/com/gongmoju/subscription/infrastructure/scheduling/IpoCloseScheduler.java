package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.ipo.IpoCloseResult;
import com.gongmoju.subscription.application.ipo.IpoCloseService;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequestedLagChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 마감 시각 + 대기 시간이 지난 OPEN 공모주를 주기적으로 마감한다 (F-13, D-16)
// 마감 조건: 대기 시간 경과(마감 대상 조회) → 접수 토픽 랙 0(여기서 확인) → 진행 중 청약 0건(IpoCloseService가 트랜잭션 안에서 확인)
// 조건이 맞지 않으면 다음 확인에서 다시 시도한다. 오래 막히면 알림 로그만 남기고 강제로 마감하지 않는다 (결정 14)
// DLT 0건 확인은 Kafka UI에서 수동으로 한다 (E-13)
// 단일 인스턴스를 전제로 한다 (알림 시각을 메모리에 둔다. 같은 공모주를 동시에 마감하려 하면 @Version으로 한쪽이 실패한다)
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ipo.close", name = "enabled", havingValue = "true", matchIfMissing = true)
class IpoCloseScheduler {

    private final IpoRepository ipoRepository;
    private final SubscriptionRequestedLagChecker lagChecker;
    private final IpoCloseService closeService;
    private final IpoCloseProperties properties;

    // 공모주별 마지막 알림 시각. 마감하면 지운다
    private final Map<UUID, Instant> lastAlertAt = new ConcurrentHashMap<>();

    IpoCloseScheduler(IpoRepository ipoRepository,
                      SubscriptionRequestedLagChecker lagChecker,
                      IpoCloseService closeService,
                      IpoCloseProperties properties) {
        this.ipoRepository = ipoRepository;
        this.lagChecker = lagChecker;
        this.closeService = closeService;
        this.properties = properties;
    }

    // 이전 실행이 끝난 뒤 interval만큼 쉬고 다시 실행한다 (실행이 겹치지 않는다)
    @Scheduled(fixedDelayString = "${ipo.close.interval:PT5S}")
    void closeDueIpos() {
        closeDueIpos(Instant.now());
    }

    // 테스트에서 현재 시각을 지정하려고 나눈다
    void closeDueIpos(Instant now) {
        List<Ipo> due = ipoRepository.findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus.OPEN, now.minus(properties.gracePeriod()));
        if (due.isEmpty()) {
            return;
        }

        // 키가 userId라서 공모주별로 나누지 않고 토픽 전체 랙을 한 번만 본다
        long lag;
        try {
            lag = lagChecker.currentLag();
        } catch (RuntimeException e) {
            log.warn("subscription.requested 랙을 조회하지 못해 이번 확인에서는 마감하지 않음. 대상 {}건", due.size(), e);
            due.forEach(ipo -> notClosed(ipo, now, "랙 조회 실패"));
            return;
        }

        for (Ipo ipo : due) {
            if (lag > 0) {
                notClosed(ipo, now, "subscription.requested 랙 " + lag);
                continue;
            }
            close(ipo, now);
        }
    }

    private void close(Ipo ipo, Instant now) {
        IpoCloseResult result;
        try {
            result = closeService.close(ipo.getId());
        } catch (RuntimeException e) {
            log.error("공모주 마감 처리 중 오류. 다음 확인에서 다시 시도함. ipoId={}", ipo.getId(), e);
            notClosed(ipo, now, "마감 처리 오류");
            return;
        }
        switch (result.outcome()) {
            case CLOSED, NOT_OPEN -> lastAlertAt.remove(ipo.getId());
            case IN_PROGRESS_REMAINING -> notClosed(ipo, now, "진행 중 청약 PENDING " + result.pendingCount()
                    + ", COMPENSATING " + result.compensatingCount()
                    + ", CANCEL_REQUESTED " + result.cancelRequestedCount());
        }
    }

    // 마감하지 못함. 대기 시간 뒤 alertAfter가 지났으면 알림 로그를 남기고, 이후 alertAfter 간격으로 다시 남긴다
    private void notClosed(Ipo ipo, Instant now, String reason) {
        Instant closableAt = ipo.getSubscriptionEndAt().plus(properties.gracePeriod());
        Instant last = lastAlertAt.get(ipo.getId());
        boolean alertDue = !now.isBefore(closableAt.plus(properties.alertAfter()))
                && (last == null || !now.isBefore(last.plus(properties.alertAfter())));
        if (!alertDue) {
            log.debug("공모주를 아직 마감하지 못함. ipoId={}, 이유={}", ipo.getId(), reason);
            return;
        }
        lastAlertAt.put(ipo.getId(), now);
        log.error("공모주 마감이 대기 시간 뒤 {} 넘게 막힘. 자동으로 마감하지 않으니 운영자 확인 필요 (margin.*.DLT, 멈춘 청약). ipoId={}, subscriptionEndAt={}, 이유={}",
                properties.alertAfter(), ipo.getId(), ipo.getSubscriptionEndAt(), reason);
    }
}
