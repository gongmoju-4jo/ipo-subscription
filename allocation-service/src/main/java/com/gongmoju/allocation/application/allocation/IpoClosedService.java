package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscriptionReader;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ipo.closed를 받아 배정을 실행한다 (이벤트 명세 ipo.closed 절).
 * 회차 시작 → 확정 청약 목록 조회 → 배정 실행 순서이고, 목록 조회는 트랜잭션 밖에서 한다 (DB 커넥션을 잡은 채 Feign 응답을 기다리지 않도록).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IpoClosedService {

    private final AllocationRoundService allocationRoundService;
    private final ConfirmedSubscriptionReader confirmedSubscriptionReader;

    /**
     * 실패하면 회차를 FAILED로 기록하고 원래 예외를 던진다. Consumer 재시도나 같은 이벤트 재수신 때 FAILED 회차를 같은 시드로 다시 실행한다.
     *
     * @param event 받은 ipo.closed
     * @return 처리 결과
     * @throws IllegalStateException 조회한 확정 청약 건수가 confirmedCount와 다르면
     * @throws RuntimeException      목록 조회, 배정 실행이 실패하면 그 예외
     */
    public IpoClosedResult handle(IpoClosed event) {
        Optional<UUID> started = allocationRoundService.start(event);
        if (started.isEmpty()) {
            return IpoClosedResult.ALREADY_DONE_IGNORED;
        }
        UUID allocationRoundId = started.get();

        try {
            List<ConfirmedSubscription> subscriptions = confirmedSubscriptionReader.readAll(event.ipoId());
            if (subscriptions.size() != event.confirmedCount()) {
                throw new IllegalStateException("조회한 확정 청약 건수가 confirmedCount와 다릅니다. ipoId=" + event.ipoId()
                        + ", confirmedCount=" + event.confirmedCount() + ", readCount=" + subscriptions.size());
            }
            return allocationRoundService.complete(allocationRoundId, subscriptions, event);
        } catch (RuntimeException e) {
            markFailed(allocationRoundId, e);
            throw e;
        }
    }

    // FAILED 기록이 실패해도 원래 예외를 던진다 (회차가 RUNNING으로 남아도 다음 시도에서 다시 실행된다)
    private void markFailed(UUID allocationRoundId, RuntimeException cause) {
        try {
            allocationRoundService.fail(allocationRoundId);
        } catch (RuntimeException e) {
            cause.addSuppressed(e);
            log.error("배정 회차를 FAILED로 기록하지 못했습니다. allocationRoundId={}", allocationRoundId, e);
        }
    }
}
