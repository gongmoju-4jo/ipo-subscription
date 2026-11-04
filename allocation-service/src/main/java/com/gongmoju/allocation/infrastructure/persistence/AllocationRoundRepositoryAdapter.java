package com.gongmoju.allocation.infrastructure.persistence;

import com.gongmoju.allocation.domain.allocation.AllocationRound;
import com.gongmoju.allocation.domain.allocation.AllocationRoundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class AllocationRoundRepositoryAdapter implements AllocationRoundRepository {

    private final AllocationRoundJpaRepository jpaRepository;

    @Override
    public Optional<AllocationRound> findById(UUID allocationRoundId) {
        return jpaRepository.findById(allocationRoundId);
    }

    @Override
    public Optional<AllocationRound> findByIpoId(UUID ipoId) {
        return jpaRepository.findByIpoId(ipoId);
    }

    @Override
    public AllocationRound save(AllocationRound round) {
        return jpaRepository.save(round);
    }
}
