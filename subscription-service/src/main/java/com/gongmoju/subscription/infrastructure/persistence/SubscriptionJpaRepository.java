package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// 조회 결과마다 종목명을 쓰므로 공모주를 같은 쿼리로 읽는다 (청약마다 공모주를 따로 조회하지 않음)
interface SubscriptionJpaRepository extends JpaRepository<Subscription, UUID> {

    @EntityGraph(attributePaths = "ipo")
    Page<Subscription> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "ipo")
    Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);

    // 응답에 공모주 정보가 없어서 공모주를 읽지 않는다.
    // 메서드 이름 쿼리(findByIpoIdAndStatus)는 ipo 테이블을 조인해 조건을 걸어서, s.ipo.id로 subscription.ipo_id 컬럼에 직접 건다
    @Query(value = "select s from Subscription s where s.ipo.id = :ipoId and s.status = :status",
            countQuery = "select count(s) from Subscription s where s.ipo.id = :ipoId and s.status = :status")
    Page<Subscription> findByIpoIdAndStatus(@Param("ipoId") UUID ipoId, @Param("status") SubscriptionStatus status,
                                            Pageable pageable);
}
