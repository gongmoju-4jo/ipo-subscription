package com.gongmoju.allocation.infrastructure.persistence;

import com.gongmoju.allocation.domain.allocation.AllocationRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface AllocationRoundJpaRepository extends JpaRepository<AllocationRound, UUID> {

    Optional<AllocationRound> findByIpoId(UUID ipoId);
}
