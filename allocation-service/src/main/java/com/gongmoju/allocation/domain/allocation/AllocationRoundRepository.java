package com.gongmoju.allocation.domain.allocation;

import java.util.Optional;
import java.util.UUID;

// 배정 회차 저장소. 구현은 infrastructure/persistence (JPA)
public interface AllocationRoundRepository {

    Optional<AllocationRound> findById(UUID allocationRoundId);
}
