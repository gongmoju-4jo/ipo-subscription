package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.MarginVoidIntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// 도메인 repository 인터페이스와 spring data jpa 구현체를 연결하는 어댑터
@Repository
@RequiredArgsConstructor
class MarginVoidIntentRepositoryAdapter implements MarginVoidIntentRepository {

    private final MarginVoidIntentJpaRepository jpaRepository;

    // subscription_id가 PK라서 PK 조회
    @Override
    public boolean existsBySubscriptionId(UUID subscriptionId) {
        return jpaRepository.existsById(subscriptionId);
    }
}
