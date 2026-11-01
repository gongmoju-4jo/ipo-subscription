package com.gongmoju.allocation.domain.allocation;

import java.util.Optional;
import java.util.UUID;

// 배정 결과 저장소. 구현은 infrastructure/persistence (JPA)
public interface AllocationRepository {

    // 본인 청약의 배정 결과. 남의 청약이면 없는 것과 같게 비어 있다 (API 명세 3.5)
    Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId);
}
