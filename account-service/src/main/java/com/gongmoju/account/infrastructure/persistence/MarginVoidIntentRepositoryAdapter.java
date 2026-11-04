package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.MarginVoidIntent;
import com.gongmoju.account.domain.margin.MarginVoidIntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository와 Spring Data JPA 구현체를 연결한다.
 */
@Repository
@RequiredArgsConstructor
class MarginVoidIntentRepositoryAdapter implements MarginVoidIntentRepository {

    private final MarginVoidIntentJpaRepository jpaRepository;

    // subscription_id가 PK라서 PK 조회
    @Override
    public boolean existsBySubscriptionId(UUID subscriptionId) {
        return jpaRepository.existsById(subscriptionId);
    }

    // margin_void_intent 저장
    @Override
    public MarginVoidIntent save(MarginVoidIntent intent) {
        return jpaRepository.save(intent);
    }
}
