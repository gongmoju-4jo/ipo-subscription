package com.gongmoju.allocation.infrastructure.persistence;

import com.gongmoju.allocation.domain.allocation.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface AllocationJpaRepository extends JpaRepository<Allocation, UUID> {

    Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId);
}
