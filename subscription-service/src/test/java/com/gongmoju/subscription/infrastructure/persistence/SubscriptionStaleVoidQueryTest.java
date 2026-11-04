package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반환 재요청 대상 조회(상태, 마지막 요청 시각, 요청 횟수, 오래된 순, 건수 제한, 반환 요청 Outbox 미발행 제외)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("반환 재요청 대상 조회 (PostgreSQL)")
class SubscriptionStaleVoidQueryTest {

    private static final String VOID_REQUESTED = "margin.void-requested";
    private static final int MAX_ATTEMPTS = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    // 응답 시간이 지났는지 가르는 기준 시각 (현재 시각 - 30초)
    private Instant threshold;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        threshold = Instant.now().minusSeconds(30);
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("마지막 요청 시각이 기준보다 오래된 COMPENSATING, CANCEL_REQUESTED만 찾고 기준 이후의 것은 찾지 않는다")
    void findStaleVoidIds_returnsOnlyOlderThanThreshold() {
        // given
        UUID compensating = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        UUID cancelRequested = insert("CANCEL_REQUESTED", 1, threshold.minusSeconds(5));
        insert("COMPENSATING", 1, threshold.plusSeconds(10));
        insert("CANCEL_REQUESTED", 1, threshold.plusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(compensating, cancelRequested);
    }

    @Test
    @DisplayName("COMPENSATING, CANCEL_REQUESTED가 아닌 청약은 마지막 요청 시각이 오래돼도 찾지 않는다")
    void findStaleVoidIds_excludesOtherStatuses() {
        // given
        UUID compensating = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        insert("PENDING", 1, threshold.minusSeconds(10));
        insert("CONFIRMED", 0, threshold.minusSeconds(10));
        insert("CANCELLED", 0, threshold.minusSeconds(10));
        insert("REJECTED", 0, threshold.minusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(compensating);
    }

    @Test
    @DisplayName("요청 횟수가 최대 횟수 미만인 청약만 재요청 대상으로 찾고, 최대 횟수 이상은 찾지 않는다")
    void findStaleVoidIds_excludesReachedMaxAttempts() {
        // given
        UUID below = insert("COMPENSATING", MAX_ATTEMPTS - 1, threshold.minusSeconds(10));
        insert("COMPENSATING", MAX_ATTEMPTS, threshold.minusSeconds(10));
        insert("CANCEL_REQUESTED", MAX_ATTEMPTS + 1, threshold.minusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(below);
    }

    @Test
    @DisplayName("마지막 요청 시각이 오래된 순으로 정렬하고 건수 제한을 넘으면 가장 오래된 것부터 자른다")
    void findStaleVoidIds_ordersOldestFirstAndLimits() {
        // given
        UUID oldest = insert("COMPENSATING", 1, threshold.minusSeconds(300));
        UUID middle = insert("CANCEL_REQUESTED", 1, threshold.minusSeconds(200));
        UUID newest = insert("COMPENSATING", 1, threshold.minusSeconds(100));

        // when
        List<UUID> all = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);
        List<UUID> limited = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 2);

        // then
        assertThat(all).containsExactly(oldest, middle, newest);
        assertThat(limited).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("반환 요청이 아직 발행되지 않은(Outbox 미발행) 청약은 찾지 않고, 발행된 뒤에는 찾는다")
    void findStaleVoidIds_excludesUnpublishedVoidRequest() {
        // given
        UUID unpublished = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        insertOutbox(unpublished, VOID_REQUESTED, null);
        UUID published = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        insertOutbox(published, VOID_REQUESTED, Instant.now());

        // when
        List<UUID> before = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);
        jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE aggregate_id = ?", unpublished);
        List<UUID> after = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(before).containsExactly(published);
        assertThat(after).containsExactlyInAnyOrder(published, unpublished);
    }

    @Test
    @DisplayName("같은 청약의 다른 토픽(인출 요청) Outbox가 미발행이어도 반환 요청이 발행됐다면 찾는다")
    void findStaleVoidIds_ignoresUnpublishedOtherTopic() {
        // given
        UUID id = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        insertOutbox(id, VOID_REQUESTED, Instant.now());
        insertOutbox(id, "margin.reserve-requested", null);

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("공모주 상태와 상관없이 CLOSED 공모주의 청약도 찾는다")
    void findStaleVoidIds_includesClosedIpo() {
        // given
        UUID id = insert("COMPENSATING", 1, threshold.minusSeconds(10));
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED' WHERE ipo_id = ?", ipoId);

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("최대 횟수에 이른 COMPENSATING, CANCEL_REQUESTED만 알림 대상으로 찾고, 미만이거나 기준 이후이거나 다른 상태는 찾지 않는다")
    void findExhaustedVoidIds_returnsOnlyReachedMaxAttemptsOlderThanThreshold() {
        // given
        UUID exhaustedCompensating = insert("COMPENSATING", MAX_ATTEMPTS, threshold.minusSeconds(10));
        UUID exhaustedCancelRequested = insert("CANCEL_REQUESTED", MAX_ATTEMPTS + 1, threshold.minusSeconds(5));
        insert("COMPENSATING", MAX_ATTEMPTS - 1, threshold.minusSeconds(10));
        insert("COMPENSATING", MAX_ATTEMPTS, threshold.plusSeconds(10));
        insert("PENDING", MAX_ATTEMPTS, threshold.minusSeconds(10));
        insert("CANCELLED", 0, threshold.minusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findExhaustedVoidIds(threshold, MAX_ATTEMPTS, 100);

        // then
        assertThat(ids).containsExactly(exhaustedCompensating, exhaustedCancelRequested);
    }

    @Test
    @DisplayName("알림 대상은 오래된 순으로 건수 제한까지만 찾는다")
    void findExhaustedVoidIds_ordersOldestFirstAndLimits() {
        // given
        UUID oldest = insert("COMPENSATING", MAX_ATTEMPTS, threshold.minusSeconds(300));
        UUID middle = insert("COMPENSATING", MAX_ATTEMPTS, threshold.minusSeconds(200));
        insert("COMPENSATING", MAX_ATTEMPTS, threshold.minusSeconds(100));

        // when
        List<UUID> limited = subscriptionRepository.findExhaustedVoidIds(threshold, MAX_ATTEMPTS, 2);

        // then
        assertThat(limited).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("반환 재요청 대상 조회용 COMPENSATING, CANCEL_REQUESTED 부분 인덱스(last_requested_at)가 만들어져 있다")
    void voidingLastRequestedIndexExists() {
        // given, when
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'subscription' AND tablename = 'subscription' "
                        + "AND indexname = 'ix_subscription_voiding_last_requested' "
                        + "AND indexdef LIKE '%(last_requested_at)%' AND indexdef LIKE '%COMPENSATING%' AND indexdef LIKE '%CANCEL_REQUESTED%'",
                Integer.class);

        // then
        assertThat(count).isEqualTo(1);
    }

    private UUID insert(String status, int attempts, Instant lastRequestedAt) {
        UUID id = UUID.randomUUID();
        boolean voiding = status.equals("COMPENSATING") || status.equals("CANCEL_REQUESTED") || status.equals("CANCELLED");
        String cancelReason = !voiding ? null : (status.equals("CANCEL_REQUESTED") ? "USER_CANCEL" : "TIMEOUT");
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, ?, ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                id, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), status, cancelReason, attempts,
                Timestamp.from(lastRequestedAt.truncatedTo(ChronoUnit.MICROS)));
        return id;
    }

    private void insertOutbox(UUID subscriptionId, String topic, Instant publishedAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox (outbox_id, aggregate_type, aggregate_id, event_type, topic, message_key,
                                    payload, created_at, published_at, retry_count)
                VALUES (?, 'SUBSCRIPTION', ?, ?, ?, ?, CAST('{}' AS JSONB), now(), ?, 0)""",
                UUID.randomUUID(), subscriptionId, topic, topic, UUID.randomUUID().toString(),
                publishedAt == null ? null : Timestamp.from(publishedAt));
    }
}
