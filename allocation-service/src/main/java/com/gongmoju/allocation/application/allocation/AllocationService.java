package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.Allocation;
import com.gongmoju.allocation.domain.allocation.AllocationCalculation;
import com.gongmoju.allocation.domain.allocation.AllocationCalculator;
import com.gongmoju.allocation.domain.allocation.AllocationEventPublisher;
import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import com.gongmoju.allocation.domain.allocation.AllocationRound;
import com.gongmoju.allocation.domain.allocation.AllocationRoundRepository;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationService {

    private final AllocationRepository allocationRepository;
    private final AllocationRoundRepository allocationRoundRepository;
    private final AllocationEventPublisher allocationEventPublisher;
    private final AllocationCalculator allocationCalculator = new AllocationCalculator();

    // 배정 결과를 DB로 보내고 메모리에서 비우는 단위. 1건당 배정 결과 1행 + Outbox 1행이라 한 번에 2만 행 (#135)
    static final int SAVE_CHUNK_SIZE = 10_000;

    /**
     * 확정 청약 목록으로 배정을 실행한다. 배정 결과 저장, allocation.result(청약 1건당 1개), allocation.finished(1개) Outbox 기록,
     * 회차 DONE을 한 트랜잭션으로 처리하므로 중간에 실패하면 모두 롤백된다.
     *
     * @param allocationRoundId 배정 회차 ID (RUNNING이어야 한다)
     * @param subscriptions     확정 청약 전체 (0건이면 allocation.finished만 기록)
     * @throws IllegalStateException 회차가 없거나 RUNNING이 아니면 (이미 끝났거나 실패한 회차는 다시 배정하지 않는다)
     */
    @Transactional
    public void allocate(UUID allocationRoundId, List<ConfirmedSubscription> subscriptions) {
        AllocationRound round = findRound(allocationRoundId);
        round.checkRunning();

        // 계산은 전체를 한 번에 한다 (추첨 시드 하나로 청약 ID 순서대로 계산해야 추첨 재현 결과가 같다)
        long calculateStartedAt = System.nanoTime();
        AllocationCalculation calculation = allocationCalculator.calculate(round.getOfferPrice(), round.getRetailQuantity(),
                round.getEqualQuantity(), round.getMinQuantity(), round.getLotterySeed(), subscriptions);
        long calculateMillis = elapsedMillis(calculateStartedAt);

        // 저장은 SAVE_CHUNK_SIZE건씩 DB로 보내고 비운다 (배정 결과, Outbox 엔티티를 전부 들고 있지 않도록, #135)
        // 커밋은 트랜잭션이 끝날 때 한 번이라 중간에 실패하면 이미 보낸 것까지 모두 롤백된다
        long saveStartedAt = System.nanoTime();
        List<AllocationCalculation.SubscriptionAllocation> results = calculation.allocations();
        for (int from = 0; from < results.size(); from += SAVE_CHUNK_SIZE) {
            // 앞 묶음의 flushAndClear로 회차도 JPA 관리에서 빠졌으므로 묶음마다 다시 조회한다 (첫 묶음은 DB 조회 없음)
            AllocationRound chunkRound = findRound(allocationRoundId);
            List<Allocation> allocations = allocationRepository.saveAll(
                    results.subList(from, Math.min(from + SAVE_CHUNK_SIZE, results.size())).stream()
                            .map(result -> Allocation.of(chunkRound, result)).toList());
            allocations.forEach(allocationEventPublisher::publishResult);
            allocationRepository.flushAndClear();
        }

        // 회차 DONE이 저장되려면 JPA가 관리하는 회차여야 해서 다시 조회한다
        AllocationRound finishingRound = findRound(allocationRoundId);
        allocationEventPublisher.publishFinished(finishingRound, calculation.totalAllocatedQuantity(), results.size());
        finishingRound.finish(Instant.now());
        // calculateMillis: 균등, 비례, 추첨 계산
        // saveMillis: 배정 결과, Outbox를 묶음마다 DB로 보내고 회차 DONE까지 (커밋 전. 마지막 기록과 커밋은 IpoClosedService의 allocateMillis에 들어간다)
        log.info("배정 계산, 저장 기록 완료 (커밋 전). allocationRoundId={}, ipoId={}, subscriptionCount={}, calculateMillis={}, saveMillis={}",
                allocationRoundId, finishingRound.getIpoId(), subscriptions.size(), calculateMillis, elapsedMillis(saveStartedAt));
    }

    private AllocationRound findRound(UUID allocationRoundId) {
        return allocationRoundRepository.findById(allocationRoundId)
                .orElseThrow(() -> new IllegalStateException("배정 회차가 없습니다. allocationRoundId=" + allocationRoundId));
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    /**
     * 본인 청약 1건의 배정 결과를 조회한다.
     * 배정 결과는 배정 완료(round DONE)와 같은 트랜잭션에서 저장되므로, 조회되면 항상 배정이 끝난 결과다.
     *
     * @param subscriptionId 청약 ID
     * @param userId         요청자 회원 ID (X-User-Id)
     * @return 배정 결과
     * @throws BusinessException 배정 전, 배정 대상이 아닌 청약, 본인 청약이 아니면 RESOURCE_NOT_FOUND(404).
     *                           남의 청약도 존재를 숨기고 같은 404로 응답한다 (API 명세 1.6, 3.5)
     */
    @Transactional(readOnly = true)
    public AllocationResult get(UUID subscriptionId, UUID userId) {
        return allocationRepository.findBySubscriptionIdAndUserId(subscriptionId, userId)
                .map(AllocationResult::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "배정 결과를 찾을 수 없습니다.", "subscriptionId"));
    }
}
