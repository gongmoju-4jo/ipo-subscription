package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.Subscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

// 조회 결과마다 종목명을 쓰므로 공모주를 같은 쿼리로 읽는다 (청약마다 공모주를 따로 조회하지 않음)
interface SubscriptionJpaRepository extends JpaRepository<Subscription, UUID> {

    @EntityGraph(attributePaths = "ipo")
    Page<Subscription> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "ipo")
    Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);
}
