package com.gongmoju.allocation.infrastructure.persistence;

import com.gongmoju.allocation.domain.allocation.Allocation;
import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class AllocationRepositoryAdapter implements AllocationRepository {

    private final AllocationJpaRepository jpaRepository;

    @Override
    public Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId) {
        return jpaRepository.findBySubscriptionIdAndUserId(subscriptionId, userId);
    }
}
