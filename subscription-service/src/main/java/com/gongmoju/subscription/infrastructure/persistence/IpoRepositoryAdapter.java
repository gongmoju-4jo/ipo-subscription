package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class IpoRepositoryAdapter implements IpoRepository {

    private final IpoJpaRepository jpaRepository;

    @Override
    public Page<Ipo> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public Optional<Ipo> findById(UUID ipoId) {
        return jpaRepository.findById(ipoId);
    }
}
