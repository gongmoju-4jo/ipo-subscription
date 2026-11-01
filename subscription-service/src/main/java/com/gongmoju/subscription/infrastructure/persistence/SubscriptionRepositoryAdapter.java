package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class SubscriptionRepositoryAdapter implements SubscriptionRepository {

    // 이중청약 방지 부분 UNIQUE(`uk_subscription_active`)와 동일한 기준으로, 이 상태가 아니면 유효한 청약으로 판단함
    private static final List<SubscriptionStatus> INACTIVE_STATUSES =
            List.of(SubscriptionStatus.REJECTED, SubscriptionStatus.CANCELLED);

    private final SubscriptionJpaRepository jpaRepository;
    private final AuditorAware<String> auditorAware;

    @Override
    public Page<Subscription> findByUserId(UUID userId, Pageable pageable) {
        return jpaRepository.findByUserId(userId, pageable);
    }

    @Override
    public Optional<Subscription> findByIdAndUserId(UUID subscriptionId, UUID userId) {
        return jpaRepository.findByIdAndUserId(subscriptionId, userId);
    }

    @Override
    public Optional<Subscription> findById(UUID subscriptionId) {
        return jpaRepository.findById(subscriptionId);
    }

    @Override
    public boolean insertIfAbsent(Subscription subscription) {
        RejectReason rejectReason = subscription.getRejectReason();
        int inserted = jpaRepository.insertIfAbsent(subscription.getId(), subscription.getRequestId(),
                subscription.getIpo().getId(), subscription.getUserId(), subscription.getAccountId(),
                subscription.getRequestedQuantity(), subscription.getMarginAmount(),
                subscription.getStatus().name(), rejectReason == null ? null : rejectReason.name(),
                subscription.getAttempts(), subscription.getLastRequestedAt(),
                subscription.getReceivedAt(), Instant.now(), auditorAware.getCurrentAuditor().orElseThrow());
        return inserted == 1;
    }

    @Override
    public Optional<Subscription> findByRequestId(UUID requestId) {
        return jpaRepository.findByRequestId(requestId);
    }

    @Override
    public boolean existsActiveByIpoIdAndUserId(UUID ipoId, UUID userId) {
        return jpaRepository.existsByIpoIdAndUserIdAndStatusNotIn(ipoId, userId, INACTIVE_STATUSES);
    }
}
