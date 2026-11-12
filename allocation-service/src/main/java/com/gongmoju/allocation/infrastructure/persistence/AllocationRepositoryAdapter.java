package com.gongmoju.allocation.infrastructure.persistence;

import com.gongmoju.allocation.domain.allocation.Allocation;
import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import com.gongmoju.allocation.domain.allocation.AllocationRound;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class AllocationRepositoryAdapter implements AllocationRepository {

    private final AllocationJpaRepository jpaRepository;

    // 같은 트랜잭션의 모든 저장소(Outbox 포함)가 함께 쓰는 영속성 컨텍스트
    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<Allocation> saveAll(List<Allocation> allocations) {
        return jpaRepository.saveAll(allocations);
    }

    // flush: 쌓아 둔 INSERT를 DB로 보낸다 (아직 커밋 전이라 다른 트랜잭션에는 보이지 않음)
    // clear: 영속성 컨텍스트를 비워서 저장한 엔티티와 변경 감지용 복사본을 GC가 회수할 수 있게 한다
    @Override
    public void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Override
    public Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId) {
        return jpaRepository.findBySubscriptionIdAndUserId(subscriptionId, userId);
    }

    @Override
    public List<Allocation> findAllByAllocationRound(AllocationRound allocationRound) {
        return jpaRepository.findAllByAllocationRound(allocationRound);
    }
}
