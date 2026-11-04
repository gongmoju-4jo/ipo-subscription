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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 응답 시간 초과 처리 대상 조회(마지막 요청 시각, 상태, 오래된 순, 건수 제한, 인출 요청 Outbox 미발행 제외)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("응답 시간 초과 처리 대상 조회 (PostgreSQL)")
class SubscriptionStalePendingQueryTest {

    private static final String RESERVE_REQUESTED = "margin.reserve-requested";

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
    @DisplayName("마지막 요청 시각이 기준보다 오래된 PENDING만 찾고 기준 이후의 PENDING은 찾지 않는다")
    void findStalePendingIds_returnsOnlyOlderThanThreshold() {
        // given
        UUID old = insert("PENDING", threshold.minusSeconds(10));
        insert("PENDING", threshold.plusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findStalePendingIds(threshold, 100);

        // then
        assertThat(ids).containsExactly(old);
    }

    @Test
    @DisplayName("PENDING이 아닌 청약은 마지막 요청 시각이 오래돼도 찾지 않는다")
    void findStalePendingIds_excludesNonPending() {
        // given
        UUID pending = insert("PENDING", threshold.minusSeconds(10));
        insert("CONFIRMED", threshold.minusSeconds(10));
        insert("COMPENSATING", threshold.minusSeconds(10));
        insert("REJECTED", threshold.minusSeconds(10));

        // when
        List<UUID> ids = subscriptionRepository.findStalePendingIds(threshold, 100);

        // then
        assertThat(ids).containsExactly(pending);
    }

    @Test
    @DisplayName("마지막 요청 시각이 오래된 순으로 정렬하고 건수 제한을 넘으면 가장 오래된 것부터 자른다")
    void findStalePendingIds_ordersOldestFirstAndLimits() {
        // given
        UUID oldest = insert("PENDING", threshold.minusSeconds(300));
        UUID middle = insert("PENDING", threshold.minusSeconds(200));
        UUID newest = insert("PENDING", threshold.minusSeconds(100));

        // when
        List<UUID> all = subscriptionRepository.findStalePendingIds(threshold, 100);
        List<UUID> limited = subscriptionRepository.findStalePendingIds(threshold, 2);

        // then
        assertThat(all).containsExactly(oldest, middle, newest);
        assertThat(limited).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("인출 요청이 아직 발행되지 않은(Outbox 미발행) 청약은 찾지 않고, 발행된 뒤에는 찾는다")
    void findStalePendingIds_excludesUnpublishedReserveRequest() {
        // given
        UUID unpublished = insert("PENDING", threshold.minusSeconds(10));
        insertOutbox(unpublished, RESERVE_REQUESTED, null);
        UUID published = insert("PENDING", threshold.minusSeconds(10));
        insertOutbox(published, RESERVE_REQUESTED, Instant.now());

        // when
        List<UUID> before = subscriptionRepository.findStalePendingIds(threshold, 100);
        jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE aggregate_id = ?", unpublished);
        List<UUID> after = subscriptionRepository.findStalePendingIds(threshold, 100);

        // then
        assertThat(before).containsExactly(published);
        assertThat(after).containsExactlyInAnyOrder(published, unpublished);
    }

    @Test
    @DisplayName("같은 청약의 다른 토픽 Outbox가 미발행이어도 인출 요청이 발행됐다면 찾는다")
    void findStalePendingIds_ignoresUnpublishedOtherTopic() {
        // given
        UUID id = insert("PENDING", threshold.minusSeconds(10));
        insertOutbox(id, RESERVE_REQUESTED, Instant.now());
        insertOutbox(id, "margin.void-requested", null);

        // when
        List<UUID> ids = subscriptionRepository.findStalePendingIds(threshold, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("공모주 상태와 상관없이 CLOSED 공모주의 PENDING도 찾는다")
    void findStalePendingIds_includesClosedIpo() {
        // given
        UUID id = insert("PENDING", threshold.minusSeconds(10));
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED' WHERE ipo_id = ?", ipoId);

        // when
        List<UUID> ids = subscriptionRepository.findStalePendingIds(threshold, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("마지막 인출 요청의 발행 시각은 발행된 인출 요청 Outbox 중 가장 늦은 시각이고, 미발행 행과 다른 토픽 행은 무시한다")
    void findLastReserveRequestPublishedAt_returnsLatestPublishedOfReserveRequests() {
        // given
        UUID id = insert("PENDING", threshold.minusSeconds(10));
        Instant first = Instant.now().minusSeconds(100);
        Instant last = Instant.now().minusSeconds(20);
        insertOutbox(id, RESERVE_REQUESTED, first);
        insertOutbox(id, RESERVE_REQUESTED, last);
        insertOutbox(id, RESERVE_REQUESTED, null);
        insertOutbox(id, "margin.void-requested", Instant.now());

        // when
        java.util.Optional<Instant> publishedAt = subscriptionRepository.findLastReserveRequestPublishedAt(id);

        // then
        assertThat(publishedAt).isPresent();
        assertThat(publishedAt.get().truncatedTo(ChronoUnit.MILLIS)).isEqualTo(last.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("발행된 인출 요청 Outbox가 없으면 마지막 발행 시각은 비어 있다")
    void findLastReserveRequestPublishedAt_isEmpty_whenNothingPublished() {
        // given
        UUID id = insert("PENDING", threshold.minusSeconds(10));
        insertOutbox(id, RESERVE_REQUESTED, null);

        // when, then
        assertThat(subscriptionRepository.findLastReserveRequestPublishedAt(id)).isEmpty();
        assertThat(subscriptionRepository.findLastReserveRequestPublishedAt(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("청약별 발행 시각 조회가 Outbox 전체를 훑지 않도록 (aggregate_id, topic) 인덱스가 만들어져 있다")
    void outboxAggregateTopicIndexExists() {
        // given, when
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'subscription' AND tablename = 'outbox' "
                        + "AND indexname = 'ix_outbox_aggregate_topic' AND indexdef LIKE '%(aggregate_id, topic)%'",
                Integer.class);

        // then
        assertThat(count).isEqualTo(1);
    }

    private UUID insert(String status, Instant lastRequestedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 1, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                id, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), status,
                java.sql.Timestamp.from(lastRequestedAt.truncatedTo(ChronoUnit.MICROS)));
        return id;
    }

    private void insertOutbox(UUID subscriptionId, String topic, Instant publishedAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox (outbox_id, aggregate_type, aggregate_id, event_type, topic, message_key,
                                    payload, created_at, published_at, retry_count)
                VALUES (?, 'SUBSCRIPTION', ?, ?, ?, ?, CAST('{}' AS JSONB), now(), ?, 0)""",
                UUID.randomUUID(), subscriptionId, topic, topic, UUID.randomUUID().toString(),
                publishedAt == null ? null : java.sql.Timestamp.from(publishedAt));
    }
}
