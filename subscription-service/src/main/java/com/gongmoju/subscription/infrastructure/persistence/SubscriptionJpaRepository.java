package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

// 조회 결과마다 종목명을 쓰므로 공모주를 같은 쿼리로 읽는다 (청약마다 공모주를 따로 조회하지 않음)
interface SubscriptionJpaRepository extends JpaRepository<Subscription, UUID> {

    @EntityGraph(attributePaths = "ipo")
    Page<Subscription> findByUserId(UUID userId, Pageable pageable);

    @EntityGraph(attributePaths = "ipo")
    Optional<Subscription> findByIdAndUserId(UUID id, UUID userId);

    Optional<Subscription> findByRequestId(UUID requestId);

    boolean existsByIpoIdAndUserIdAndStatusNotIn(UUID ipoId, UUID userId, Collection<SubscriptionStatus> statuses);

    /**
     * 네이티브 쿼리는 JPA Auditing과 {@code @Version}을 거치지 않으므로 {@code created_*}, {@code updated_*}, {@code version}을 직접 채움.
     * {@code ON CONFLICT} 대상을 {@code request_id}로 명시하여 다른 UNIQUE 위반({@code uk_subscription_active})은 예외로 전파됨. 트랜잭션 안에서 호출해야 함.
     */
    @Modifying
    @Query(value = """
            INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                      margin_amount, status, reject_reason, attempts, last_requested_at, received_at,
                                      version, created_at, created_by, updated_at, updated_by)
            VALUES (:id, :requestId, :ipoId, :userId, :accountId, :requestedQuantity,
                    :marginAmount, :status, CAST(:rejectReason AS VARCHAR), :attempts,
                    CAST(:lastRequestedAt AS TIMESTAMPTZ), :receivedAt, 0,
                    :now, :auditor, :now, :auditor)
            ON CONFLICT (request_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("requestId") UUID requestId, @Param("ipoId") UUID ipoId,
                       @Param("userId") UUID userId, @Param("accountId") UUID accountId,
                       @Param("requestedQuantity") long requestedQuantity, @Param("marginAmount") long marginAmount,
                       @Param("status") String status, @Param("rejectReason") String rejectReason,
                       @Param("attempts") int attempts, @Param("lastRequestedAt") Instant lastRequestedAt,
                       @Param("receivedAt") Instant receivedAt, @Param("now") Instant now,
                       @Param("auditor") String auditor);

    // 응답에 공모주 정보가 없어서 공모주를 읽지 않는다.
    // 메서드 이름 쿼리(findByIpoIdAndStatus)는 ipo 테이블을 조인해 조건을 걸어서, s.ipo.id로 subscription.ipo_id 컬럼에 직접 건다
    @Query(value = "select s from Subscription s where s.ipo.id = :ipoId and s.status = :status",
            countQuery = "select count(s) from Subscription s where s.ipo.id = :ipoId and s.status = :status")
    Page<Subscription> findByIpoIdAndStatus(@Param("ipoId") UUID ipoId, @Param("status") SubscriptionStatus status,
                                            Pageable pageable);

    // 위와 같은 이유로 s.ipo.id에 직접 건다
    @Query("select count(s) from Subscription s where s.ipo.id = :ipoId and s.status = :status")
    long countByIpoIdAndStatus(@Param("ipoId") UUID ipoId, @Param("status") SubscriptionStatus status);
}
