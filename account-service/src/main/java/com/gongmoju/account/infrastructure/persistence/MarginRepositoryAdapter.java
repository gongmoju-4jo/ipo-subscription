package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.domain.margin.MarginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// 도메인 repository 인터페이서와 spring data jpa 구현체를 연결하는 어댑터
@Repository
@RequiredArgsConstructor
class MarginRepositoryAdapter implements MarginRepository {

    private final MarginJpaRepository jpaRepository;

    @Override
    public Margin save(Margin margin) {
        return jpaRepository.save(margin);
    }

    @Override
    public Optional<Margin> findBySubscriptionId(UUID subscriptionId) {
        return jpaRepository.findBySubscriptionId(subscriptionId);
    }
}
