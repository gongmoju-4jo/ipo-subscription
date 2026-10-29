package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.domain.margin.MarginRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// 도메인 메서드 이름이 Spring Data 쿼리 규칙에 묶이지 않게 JPA 저장소를 감싼다
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
