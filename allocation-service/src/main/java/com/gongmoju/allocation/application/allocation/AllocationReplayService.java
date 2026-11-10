package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.Allocation;
import com.gongmoju.allocation.domain.allocation.AllocationCalculation;
import com.gongmoju.allocation.domain.allocation.AllocationCalculation.SubscriptionAllocation;
import com.gongmoju.allocation.domain.allocation.AllocationCalculator;
import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import com.gongmoju.allocation.domain.allocation.AllocationRound;
import com.gongmoju.allocation.domain.allocation.AllocationRoundRepository;
import com.gongmoju.allocation.domain.allocation.AllocationRoundStatus;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 추첨 재현: 배정이 끝난 공모주를 회차에 저장한 공모주 조건, lotterySeed와 배정 결과에 저장한 청약 수량으로 다시 계산해
// 저장된 배정 결과와 청약마다 비교한다. 읽기만 하고 아무것도 바꾸지 않는다
@Service
@RequiredArgsConstructor
public class AllocationReplayService {

    private final AllocationRepository allocationRepository;
    private final AllocationRoundRepository allocationRoundRepository;
    private final AllocationCalculator allocationCalculator = new AllocationCalculator();

    /**
     * 공모주의 배정을 다시 계산해 저장된 배정 결과와 비교한다.
     * 배정 결과를 모두 메모리에 올려 다시 계산하므로 시간과 메모리가 배정과 비슷하게 든다.
     *
     * @param ipoId 공모주 ID
     * @return 재현 결과 (일치 여부, 비교한 청약 수, 결과가 다른 청약)
     * @throws BusinessException 배정 회차가 없으면 RESOURCE_NOT_FOUND(404),
     *                           배정이 끝나지 않았거나(DONE이 아님) 청약 수량이 저장되지 않은 배정 결과가 있으면 BUSINESS_RULE_VIOLATION(422)
     */
    @Transactional(readOnly = true)
    public AllocationReplayResult replay(UUID ipoId) {
        AllocationRound round = allocationRoundRepository.findByIpoId(ipoId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "배정 회차를 찾을 수 없습니다.", "ipoId"));
        if (round.getStatus() != AllocationRoundStatus.DONE) {
            throw new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION,
                    "배정이 끝난 공모주만 재현할 수 있습니다. status=" + round.getStatus(), "ipoId");
        }

        List<Allocation> allocations = allocationRepository.findAllByAllocationRound(round);
        AllocationCalculation calculation = allocationCalculator.calculate(round.getOfferPrice(), round.getRetailQuantity(),
                round.getEqualQuantity(), round.getMinQuantity(), round.getLotterySeed(), readInputs(allocations));

        Map<UUID, SubscriptionAllocation> replayed = calculation.allocations().stream()
                .collect(Collectors.toMap(result -> result.subscription().subscriptionId(), Function.identity()));
        List<UUID> mismatched = allocations.stream()
                .filter(allocation -> !allocation.hasSameResult(replayed.get(allocation.getSubscriptionId())))
                .map(Allocation::getSubscriptionId)
                .sorted(Comparator.naturalOrder())
                .toList();
        return new AllocationReplayResult(ipoId, mismatched.isEmpty(), allocations.size(), mismatched);
    }

    // 재현 입력: 배정 결과에 저장한 청약 수량. 입력을 저장하는 곳이 바뀌면 이 메서드만 바꾼다
    private List<ConfirmedSubscription> readInputs(List<Allocation> allocations) {
        return allocations.stream()
                .map(allocation -> {
                    if (allocation.getRequestedQuantity() == null) {
                        throw new BusinessException(CommonErrorCode.BUSINESS_RULE_VIOLATION,
                                "청약 수량이 저장되지 않은 배정 결과가 있어 재현할 수 없습니다.", "ipoId");
                    }
                    return new ConfirmedSubscription(allocation.getSubscriptionId(), allocation.getUserId(),
                            allocation.getAccountId(), allocation.getRequestedQuantity());
                })
                .toList();
    }
}
