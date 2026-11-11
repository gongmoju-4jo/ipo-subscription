package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class IpoRepositoryAdapter implements IpoRepository {

    private final IpoJpaRepository jpaRepository;
    private final EntityManager entityManager;

    @Override
    public Page<Ipo> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Optional<Ipo> findById(UUID ipoId) {
        return jpaRepository.findById(ipoId);
    }

    @Override
    public List<Ipo> findByStatus(IpoStatus status) {
        return jpaRepository.findByStatus(status);
    }

    @Override
    public Optional<Ipo> findByIdForUpdate(UUID ipoId) {
        return jpaRepository.findByIdForUpdate(ipoId);
    }

    @Override
    public void refreshForShare(Ipo ipo) {
        entityManager.refresh(ipo, LockModeType.PESSIMISTIC_READ);
    }

    @Override
    public List<Ipo> findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus status, Instant endAt) {
        return jpaRepository.findByStatusAndSubscriptionEndAtLessThanEqual(status, endAt);
    }
}
