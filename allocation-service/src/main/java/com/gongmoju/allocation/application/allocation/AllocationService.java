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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AllocationService {

    private final AllocationRepository allocationRepository;
    private final AllocationRoundRepository allocationRoundRepository;
    private final AllocationEventPublisher allocationEventPublisher;
    private final AllocationCalculator allocationCalculator = new AllocationCalculator();

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
        AllocationRound round = allocationRoundRepository.findById(allocationRoundId)
                .orElseThrow(() -> new IllegalStateException("배정 회차가 없습니다. allocationRoundId=" + allocationRoundId));
        round.checkRunning();

        AllocationCalculation calculation = allocationCalculator.calculate(round.getOfferPrice(), round.getRetailQuantity(),
                round.getEqualQuantity(), round.getMinQuantity(), round.getLotterySeed(), subscriptions);
        List<Allocation> allocations = allocationRepository.saveAll(
                calculation.allocations().stream().map(result -> Allocation.of(round, result)).toList());

        allocations.forEach(allocationEventPublisher::publishResult);
        allocationEventPublisher.publishFinished(round, calculation.totalAllocatedQuantity(), allocations.size());
        round.finish(Instant.now());
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
