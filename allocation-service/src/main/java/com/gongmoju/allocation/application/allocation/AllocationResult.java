package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.Allocation;

import java.util.UUID;

/**
 * 배정 결과 조회 결과. 트랜잭션 밖(컨트롤러)에서 엔티티를 다루지 않도록 값만 복사해 둔다.
 */
public record AllocationResult(
        UUID ipoId,
        UUID subscriptionId,
        long equalAllocatedQuantity,
        long proportionalAllocatedQuantity,
        long allocatedQuantity,
        long allocatedAmount
) {

    public static AllocationResult from(Allocation allocation) {
        return new AllocationResult(allocation.getIpoId(), allocation.getSubscriptionId(),
                allocation.getEqualAllocatedQuantity(), allocation.getProportionalAllocatedQuantity(),
                allocation.getAllocatedQuantity(), allocation.getAllocatedAmount());
    }
}
