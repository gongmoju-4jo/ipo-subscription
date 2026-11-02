package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class SubscriptionRepositoryAdapter implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpaRepository;

    @Override
    public Page<Subscription> findByUserId(UUID userId, Pageable pageable) {
        return jpaRepository.findByUserId(userId, pageable);
    }

    @Override
    public Optional<Subscription> findByIdAndUserId(UUID subscriptionId, UUID userId) {
        return jpaRepository.findByIdAndUserId(subscriptionId, userId);
    }

    @Override
    public Page<Subscription> findByIpoIdAndStatus(UUID ipoId, SubscriptionStatus status, Pageable pageable) {
        return jpaRepository.findByIpoIdAndStatus(ipoId, status, pageable);
    }
}
