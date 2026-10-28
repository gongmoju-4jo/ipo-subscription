package com.gongmoju.allocation.domain.allocation;

import java.util.List;

/**
 * 배정 계산 결과. 청약 미달이 아니면 배정 수량 합 + fractionalShareQuantity = retailQuantity.
 *
 * @param allocations             청약별 배정 결과 (입력 순서 그대로, 0주 포함)
 * @param fractionalShareQuantity 비례 계산에서 버림으로 남은 수량 (단주 재배정 대상, 아직 배정하지 않음)
 * @param undersubscribedQuantity 청약 미달로 남은 수량 (배정하지 않음)
 */
public record AllocationCalculation(
        List<SubscriptionAllocation> allocations,
        long fractionalShareQuantity,
        long undersubscribedQuantity
) {

    public long totalAllocatedQuantity() {
        return allocations.stream().mapToLong(SubscriptionAllocation::allocatedQuantity).sum();
    }

    /**
     * 청약 1건의 배정 결과.
     *
     * @param subscription                  배정 대상 청약
     * @param equalAllocatedQuantity        균등 배정 수량
     * @param proportionalAllocatedQuantity 비례 배정 수량
     * @param allocatedQuantity             배정 수량 (균등 + 비례)
     * @param allocatedAmount               배정 대금 (배정 수량 x 공모가)
     */
    public record SubscriptionAllocation(
            ConfirmedSubscription subscription,
            long equalAllocatedQuantity,
            long proportionalAllocatedQuantity,
            long allocatedQuantity,
            long allocatedAmount
    ) {
    }
}
