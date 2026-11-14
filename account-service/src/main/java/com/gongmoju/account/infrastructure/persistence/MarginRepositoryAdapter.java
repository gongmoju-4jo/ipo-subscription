package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.domain.margin.MarginRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

// 도메인 repository 인터페이서와 spring data jpa 구현체를 연결하는 어댑터
@Repository
@RequiredArgsConstructor
class MarginRepositoryAdapter implements MarginRepository {

    private final MarginJpaRepository jpaRepository;

    // 같은 트랜잭션의 모든 저장소(계좌, 원장, Outbox 포함)가 함께 쓰는 영속성 컨텍스트
    @PersistenceContext
    private EntityManager entityManager;

    // flush: 쌓아 둔 INSERT, UPDATE를 DB로 보낸다 (아직 커밋 전이라 다른 트랜잭션에는 보이지 않음)
    // clear: 영속성 컨텍스트를 비워서 처리를 끝낸 엔티티와 변경 감지용 복사본을 GC가 회수할 수 있게 한다
    @Override
    public void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Override
    public Margin save(Margin margin) {
        return jpaRepository.save(margin);
    }

    @Override
    public Optional<Margin> findBySubscriptionId(UUID subscriptionId) {
        return jpaRepository.findBySubscriptionId(subscriptionId);
    }
}
