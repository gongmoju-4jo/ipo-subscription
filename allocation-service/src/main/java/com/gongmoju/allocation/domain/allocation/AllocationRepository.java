package com.gongmoju.allocation.domain.allocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 배정 결과 저장소. 구현은 infrastructure/persistence (JPA)
public interface AllocationRepository {

    // 저장 후 ID가 채워진 결과를 돌려준다 (allocation.result의 aggregate_id로 씀)
    List<Allocation> saveAll(List<Allocation> allocations);

    // 본인 청약의 배정 결과. 남의 청약이면 없는 것과 같게 비어 있다 (API 명세 3.5)
    Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId);

    // 회차의 배정 결과 전체 (재현용)
    List<Allocation> findAllByAllocationRound(AllocationRound allocationRound);
}
