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
import java.util.List;
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

    // 메서드 이름 쿼리는 ipo 테이블을 조인하므로 s.ipo.id로 subscription.ipo_id 컬럼에 직접 건다 (아래 findByIpoIdAndStatus와 같은 이유)
    @Query("select count(s) from Subscription s where s.ipo.id = :ipoId and s.status in :statuses")
    long countByIpoIdAndStatusIn(@Param("ipoId") UUID ipoId, @Param("statuses") Collection<SubscriptionStatus> statuses);

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

    /**
    * 마지막 요청 시각이 기준 시각보다 이른 {@code PENDING}청약의 {@code subscription_id}를 오래된 순으로 최대 limit건 조회함.
    * 미발행 증거금 인출 요청(margin.reserve-requested)이 있는 청약은 제외함.
    */
    @Query(value = """
            SELECT s.subscription_id FROM subscription s
            WHERE s.status = 'PENDING'
              AND s.last_requested_at < :lastRequestedBefore
              AND NOT EXISTS (SELECT 1 FROM outbox o
                              WHERE o.aggregate_id = s.subscription_id
                                AND o.topic = 'margin.reserve-requested'
                                AND o.published_at IS NULL)
            ORDER BY s.last_requested_at ASC
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findStalePendingIds(@Param("lastRequestedBefore") Instant lastRequestedBefore, @Param("limit") int limit);

    /**
     * 마지막 요청 시각에서 대기 시간(백오프)이 지난 {@code COMPENSATING}, {@code CANCEL_REQUESTED} 청약의 {@code subscription_id}를 오래된 순으로 최대 limit건 조회함.
     * 대기 시간 = min(initialSeconds × 2^(요청 횟수 − 1), maxSeconds) (VoidRetryBackoff와 같은 식). 요청 횟수는 1 미만이면 1, 지수는 19까지만 씀. 최대 횟수는 없음.
     * 미발행 반환 요청(margin.void-requested)이 있는 청약은 제외함.
     * 기준 시각 :now는 타입이 정해지지 않으면 interval 뺄셈으로 해석되므로 timestamptz로 변환함.
     */
    @Query(value = """
            SELECT s.subscription_id FROM subscription s
            WHERE s.status IN ('COMPENSATING', 'CANCEL_REQUESTED')
              AND s.last_requested_at < CAST(:now AS timestamptz)
                    - make_interval(secs => LEAST(:initialSeconds * power(2, LEAST(GREATEST(s.attempts, 1), 20) - 1), :maxSeconds))
              AND NOT EXISTS (SELECT 1 FROM outbox o
                              WHERE o.aggregate_id = s.subscription_id
                                AND o.topic = 'margin.void-requested'
                                AND o.published_at IS NULL)
            ORDER BY s.last_requested_at ASC
            LIMIT :limit""", nativeQuery = true)
    List<UUID> findStaleVoidIds(@Param("now") Instant now, @Param("initialSeconds") double initialSeconds,
                                @Param("maxSeconds") double maxSeconds, @Param("limit") int limit);

    // 알림 대상: 요청 횟수가 알림 기준 이상이고 마지막 요청 뒤 응답 대기 시간이 지난 반환 중 청약. 오래된 순.
    // 응답 대기 시간(:minAgeSeconds)은 방금 요청해 응답이 오는 중인 청약을 알리지 않게 하는 값 (재요청 대기 시간과 달리 요청 횟수와 무관한 고정값).
    // 재요청 대기 시간(백오프)을 조건으로 쓰면, 상한 간격에서는 재요청 직전 몇 초만 대상이 되어 알림 시점에 거의 잡히지 않음.
    // 읽기만 하며 청약을 바꾸지 않음. Outbox 확인은 필요 없음
    @Query(value = """
            SELECT s.* FROM subscription s
            WHERE s.status IN ('COMPENSATING', 'CANCEL_REQUESTED')
              AND s.attempts >= :alertAttempts
              AND s.last_requested_at < CAST(:now AS timestamptz) - make_interval(secs => :minAgeSeconds)
            ORDER BY s.last_requested_at ASC
            LIMIT :limit""", nativeQuery = true)
    List<Subscription> findLongVoiding(@Param("now") Instant now, @Param("alertAttempts") int alertAttempts,
                                       @Param("minAgeSeconds") double minAgeSeconds, @Param("limit") int limit);

    // 위 조회와 같은 조건의 총 건수 (건수 제한 없음)
    @Query(value = """
            SELECT count(*) FROM subscription s
            WHERE s.status IN ('COMPENSATING', 'CANCEL_REQUESTED')
              AND s.attempts >= :alertAttempts
              AND s.last_requested_at < CAST(:now AS timestamptz) - make_interval(secs => :minAgeSeconds)""", nativeQuery = true)
    long countLongVoiding(@Param("now") Instant now, @Param("alertAttempts") int alertAttempts,
                          @Param("minAgeSeconds") double minAgeSeconds);

    // 발행된 증거금 인출 요청 Outbox 중 가장 늦은 발행 시각. 없으면 null
    @Query("select max(o.publishedAt) from Outbox o where o.aggregateId = :subscriptionId "
            + "and o.topic = 'margin.reserve-requested' and o.publishedAt is not null")
    Instant findLastReserveRequestPublishedAt(@Param("subscriptionId") UUID subscriptionId);

    // 응답에 공모주 정보가 없어서 공모주를 읽지 않는다.
    // 메서드 이름 쿼리(findByIpoIdAndStatus)는 ipo 테이블을 조인해 조건을 걸어서, s.ipo.id로 subscription.ipo_id 컬럼에 직접 건다
    @Query(value = "select s from Subscription s where s.ipo.id = :ipoId and s.status = :status",
            countQuery = "select count(s) from Subscription s where s.ipo.id = :ipoId and s.status = :status")
    Page<Subscription> findByIpoIdAndStatus(@Param("ipoId") UUID ipoId, @Param("status") SubscriptionStatus status,
                                            Pageable pageable);

    // 확정 청약 키셋 조회 (#138). 상태를 바인딩 값이 아니라 SQL에 'CONFIRMED'로 적어야 부분 인덱스 ix_subscription_confirmed_ipo를 쓴다
    // (바인딩 값이면 재사용하는 실행 계획이 어떤 상태인지 몰라 부분 인덱스를 고를 수 없음). 첫 요청과 다음 요청을 나눠 ID 위치로 바로 찾게 한다
    @Query(value = """
            SELECT * FROM subscription s
            WHERE s.ipo_id = :ipoId AND s.status = 'CONFIRMED'
            ORDER BY s.subscription_id
            LIMIT :limit""", nativeQuery = true)
    List<Subscription> findConfirmedByIpoId(@Param("ipoId") UUID ipoId, @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM subscription s
            WHERE s.ipo_id = :ipoId AND s.status = 'CONFIRMED' AND s.subscription_id > :afterSubscriptionId
            ORDER BY s.subscription_id
            LIMIT :limit""", nativeQuery = true)
    List<Subscription> findConfirmedByIpoIdAfter(@Param("ipoId") UUID ipoId,
                                                 @Param("afterSubscriptionId") UUID afterSubscriptionId,
                                                 @Param("limit") int limit);
}
