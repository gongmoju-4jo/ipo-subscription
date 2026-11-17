package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 반환 재요청 대상 조회(상태, 요청 횟수별 대기 시간, 오래된 순, 건수 제한, 반환 요청 Outbox 미발행 제외)와
 * 오래 걸리는 반환 알림 조회(읽기 전용)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("반환 재요청 대상 조회와 반환 지연 알림 조회 (PostgreSQL)")
class SubscriptionStaleVoidQueryTest {

    private static final String VOID_REQUESTED = "margin.void-requested";
    // 기본 설정과 같은 값 (요청 횟수 1, 2, 3, 4, 5 이상일 때 30초, 60초, 120초, 240초, 300초)
    private static final VoidRetryBackoff BACKOFF = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(5));
    private static final int ALERT_ATTEMPTS = 5;
    // 알림 조회에서 방금 요청한 청약을 빼는 시간 (기본 설정의 초기 대기 시간과 같음)
    private static final Duration MIN_AGE = Duration.ofSeconds(30);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    // 재요청 판단 기준 시각. 마이크로초로 맞춤 (PostgreSQL 저장 정밀도와 같게 해서 경계 값 비교가 흔들리지 않게 함)
    private Instant now;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
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

    // 요청 횟수 attempts의 대기 시간보다 extraSeconds초 더 지난 마지막 요청 시각
    private Instant overdue(int attempts, long extraSeconds) {
        return now.minus(BACKOFF.waitAfter(attempts)).minusSeconds(extraSeconds);
    }

    @Test
    @DisplayName("마지막 요청 뒤 대기 시간이 지난 COMPENSATING, CANCEL_REQUESTED만 찾고 아직 안 지난 것은 찾지 않는다")
    void findStaleVoidIds_returnsOnlyAfterWait() {
        // given
        UUID compensating = insert("COMPENSATING", 1, overdue(1, 10));
        UUID cancelRequested = insert("CANCEL_REQUESTED", 1, overdue(1, 5));
        insert("COMPENSATING", 1, overdue(1, -10));
        insert("CANCEL_REQUESTED", 1, overdue(1, -10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactly(compensating, cancelRequested);
    }

    @Test
    @DisplayName("COMPENSATING, CANCEL_REQUESTED가 아닌 청약은 마지막 요청 시각이 오래돼도 찾지 않는다")
    void findStaleVoidIds_excludesOtherStatuses() {
        // given
        UUID compensating = insert("COMPENSATING", 1, overdue(1, 10));
        insert("PENDING", 1, overdue(1, 10));
        insert("CONFIRMED", 0, overdue(1, 10));
        insert("CANCELLED", 0, overdue(1, 10));
        insert("REJECTED", 0, overdue(1, 10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactly(compensating);
    }

    @Test
    @DisplayName("요청 횟수가 늘수록 대기 시간이 길어져서(30초에서 시작해 2배, 5분 상한) 같은 시각에도 횟수가 많은 청약은 대기 시간이 지날 때까지 찾지 않는다")
    void findStaleVoidIds_usesLongerWaitForMoreAttempts() {
        // given: 마지막 요청이 100초 전. 요청 횟수 1, 2는 대기 시간(30, 60초)이 지났고 3, 4, 5는 지나지 않음
        Instant hundredSecondsAgo = now.minusSeconds(100);
        UUID attempts1 = insert("COMPENSATING", 1, hundredSecondsAgo);
        UUID attempts2 = insert("COMPENSATING", 2, hundredSecondsAgo.minusMillis(1));
        insert("COMPENSATING", 3, hundredSecondsAgo.plusMillis(1));
        insert("COMPENSATING", 4, hundredSecondsAgo);
        insert("COMPENSATING", 5, hundredSecondsAgo);

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactlyInAnyOrder(attempts1, attempts2);
    }

    @Test
    @DisplayName("요청 횟수에 상한이 없어서 이전 최대 횟수(3) 이상이어도 대기 시간이 지났으면 계속 찾는다")
    void findStaleVoidIds_hasNoMaxAttempts() {
        // given
        UUID three = insert("COMPENSATING", 3, overdue(3, 10));
        UUID five = insert("CANCEL_REQUESTED", 5, overdue(5, 10));
        UUID fifty = insert("COMPENSATING", 50, overdue(50, 10));

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactlyInAnyOrder(three, five, fifty);
    }

    @Test
    @DisplayName("SQL의 대기 시간 식이 코드의 VoidRetryBackoff.waitAfter와 같다 (요청 횟수 0~100, 대기 시간보다 1밀리초 더 지난 것만 찾고, 같거나 1밀리초 덜 지난 것은 찾지 않는다)")
    void findStaleVoidIds_matchesBackoffWaitAfter() {
        // given
        int[] attemptsList = {0, 1, 2, 3, 4, 5, 6, 7, 10, 19, 20, 21, 25, 100};
        List<UUID> expected = new ArrayList<>();
        for (int attempts : attemptsList) {
            Instant boundary = now.minus(BACKOFF.waitAfter(attempts));
            expected.add(insert("COMPENSATING", attempts, boundary.minusMillis(1)));
            insert("COMPENSATING", attempts, boundary);
            insert("COMPENSATING", attempts, boundary.plusMillis(1));
        }

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 1000);

        // then
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("설정이 달라도(초기 10초, 상한 25초) SQL과 코드의 대기 시간이 같다")
    void findStaleVoidIds_matchesBackoffWaitAfter_withOtherSettings() {
        // given
        VoidRetryBackoff other = new VoidRetryBackoff(Duration.ofSeconds(10), Duration.ofSeconds(25));
        List<UUID> expected = new ArrayList<>();
        for (int attempts : new int[]{1, 2, 3, 4, 8}) {
            Instant boundary = now.minus(other.waitAfter(attempts));
            expected.add(insert("COMPENSATING", attempts, boundary.minusMillis(1)));
            insert("COMPENSATING", attempts, boundary);
        }

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, other, 1000);

        // then
        assertThat(ids).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("마지막 요청 시각이 오래된 순으로 정렬하고 건수 제한을 넘으면 가장 오래된 것부터 자른다")
    void findStaleVoidIds_ordersOldestFirstAndLimits() {
        // given
        UUID oldest = insert("COMPENSATING", 1, overdue(1, 300));
        UUID middle = insert("CANCEL_REQUESTED", 1, overdue(1, 200));
        UUID newest = insert("COMPENSATING", 1, overdue(1, 100));

        // when
        List<UUID> all = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);
        List<UUID> limited = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 2);

        // then
        assertThat(all).containsExactly(oldest, middle, newest);
        assertThat(limited).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("재요청해서 마지막 요청 시각이 새로워진 청약은 순서 뒤로 가서, 오래 남은 청약이 건수 제한을 계속 차지하지 않는다")
    void findStaleVoidIds_movesRetriedToBack() {
        // given: 오래 남은 청약 2건이 건수 제한(2)을 채우고 있고, 그 뒤에 대기 시간이 지난 청약이 1건 더 있음
        UUID first = insert("COMPENSATING", 10, overdue(10, 600));
        UUID second = insert("COMPENSATING", 10, overdue(10, 500));
        UUID third = insert("COMPENSATING", 1, overdue(1, 10));
        List<UUID> before = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 2);

        // when: 앞의 두 건을 재요청한 것처럼 마지막 요청 시각을 지금으로 갱신
        jdbcTemplate.update("UPDATE subscription SET attempts = attempts + 1, last_requested_at = ? WHERE subscription_id IN (?, ?)",
                Timestamp.from(now), first, second);
        List<UUID> after = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 2);

        // then
        assertThat(before).containsExactly(first, second);
        assertThat(after).containsExactly(third);
    }

    @Test
    @DisplayName("반환 요청이 아직 발행되지 않은(Outbox 미발행) 청약은 찾지 않고, 발행된 뒤에는 찾는다")
    void findStaleVoidIds_excludesUnpublishedVoidRequest() {
        // given
        UUID unpublished = insert("COMPENSATING", 1, overdue(1, 10));
        insertOutbox(unpublished, VOID_REQUESTED, null);
        UUID published = insert("COMPENSATING", 1, overdue(1, 10));
        insertOutbox(published, VOID_REQUESTED, Instant.now());

        // when
        List<UUID> before = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);
        jdbcTemplate.update("UPDATE outbox SET published_at = now() WHERE aggregate_id = ?", unpublished);
        List<UUID> after = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(before).containsExactly(published);
        assertThat(after).containsExactlyInAnyOrder(published, unpublished);
    }

    @Test
    @DisplayName("같은 청약의 다른 토픽(인출 요청) Outbox가 미발행이어도 반환 요청이 발행됐다면 찾는다")
    void findStaleVoidIds_ignoresUnpublishedOtherTopic() {
        // given
        UUID id = insert("COMPENSATING", 1, overdue(1, 10));
        insertOutbox(id, VOID_REQUESTED, Instant.now());
        insertOutbox(id, "margin.reserve-requested", null);

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("공모주 상태와 상관없이 CLOSED 공모주의 청약도 찾는다")
    void findStaleVoidIds_includesClosedIpo() {
        // given
        UUID id = insert("COMPENSATING", 1, overdue(1, 10));
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED' WHERE ipo_id = ?", ipoId);

        // when
        List<UUID> ids = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(ids).containsExactly(id);
    }

    @Test
    @DisplayName("요청 횟수가 알림 기준(5) 이상이고 마지막 요청 뒤 30초가 지난 COMPENSATING, CANCEL_REQUESTED만 알림 대상으로 찾고, 기준 미만이거나 방금 요청했거나 다른 상태는 찾지 않는다")
    void findLongVoiding_returnsOnlyAtOrAboveAlertAttemptsAndOlderThanMinAge() {
        // given
        UUID longCompensating = insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(60));
        UUID longCancelRequested = insert("CANCEL_REQUESTED", ALERT_ATTEMPTS + 4, now.minusSeconds(31));
        insert("COMPENSATING", ALERT_ATTEMPTS - 1, now.minusSeconds(60));
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(10));
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minus(MIN_AGE));
        insert("PENDING", ALERT_ATTEMPTS, now.minusSeconds(60));
        insert("CANCELLED", 0, now.minusSeconds(60));

        // when
        List<Subscription> found = subscriptionRepository.findLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE, 100);

        // then
        assertThat(found).extracting(Subscription::getId).containsExactly(longCompensating, longCancelRequested);
    }

    @Test
    @DisplayName("알림 조회는 로그에 필요한 청약 정보(상태, 해제 사유, 요청 횟수, 마지막 요청 시각)를 직접 돌려주고, 재요청 대기 시간과 상관없이 대기 중인 청약도 찾는다")
    void findLongVoiding_returnsDetails_evenWhileWaitingForNextRetry() {
        // given: 요청 횟수 5의 대기 시간(300초)이 아직 안 지났음(60초 전에 요청). 재요청 대상은 아니지만 알림 대상임
        Instant lastRequestedAt = now.minusSeconds(60);
        UUID id = insert("COMPENSATING", 5, lastRequestedAt);

        // when
        List<Subscription> found = subscriptionRepository.findLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE, 100);
        List<UUID> retryTargets = subscriptionRepository.findStaleVoidIds(now, BACKOFF, 100);

        // then
        assertThat(found).hasSize(1);
        Subscription subscription = found.get(0);
        assertThat(subscription.getId()).isEqualTo(id);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.COMPENSATING);
        assertThat(subscription.getCancelReason()).isEqualTo(CancelReason.TIMEOUT);
        assertThat(subscription.getAttempts()).isEqualTo(5);
        assertThat(subscription.getLastRequestedAt()).isEqualTo(lastRequestedAt);
        assertThat(retryTargets).isEmpty();
    }

    @Test
    @DisplayName("알림 조회는 반환 요청 Outbox가 미발행이어도 찾는다 (Outbox를 보지 않음)")
    void findLongVoiding_ignoresUnpublishedOutbox() {
        // given
        UUID id = insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(60));
        insertOutbox(id, VOID_REQUESTED, null);

        // when
        List<Subscription> found = subscriptionRepository.findLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE, 100);

        // then
        assertThat(found).extracting(Subscription::getId).containsExactly(id);
    }

    @Test
    @DisplayName("알림 대상은 오래된 순으로 건수 제한까지만 찾는다")
    void findLongVoiding_ordersOldestFirstAndLimits() {
        // given
        UUID oldest = insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(300));
        UUID middle = insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(200));
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(100));

        // when
        List<Subscription> limited = subscriptionRepository.findLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE, 2);

        // then
        assertThat(limited).extracting(Subscription::getId).containsExactly(oldest, middle);
    }

    @Test
    @DisplayName("반환 지연 청약의 총 건수는 건수 제한과 상관없이 같은 조건(상태, 요청 횟수, 마지막 요청 뒤 경과 시간)으로 센다")
    void countLongVoiding_countsAllRegardlessOfLimit() {
        // given
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(300));
        insert("CANCEL_REQUESTED", ALERT_ATTEMPTS + 4, now.minusSeconds(200));
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(100));
        insert("COMPENSATING", ALERT_ATTEMPTS - 1, now.minusSeconds(100));
        insert("COMPENSATING", ALERT_ATTEMPTS, now.minusSeconds(10));
        insert("PENDING", ALERT_ATTEMPTS, now.minusSeconds(100));
        insert("CANCELLED", 0, now.minusSeconds(100));

        // when
        long total = subscriptionRepository.countLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE);
        List<Subscription> limited = subscriptionRepository.findLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE, 2);

        // then
        assertThat(total).isEqualTo(3);
        assertThat(limited).hasSize(2);
    }

    @Test
    @DisplayName("반환 지연 청약이 없으면 총 건수는 0이다")
    void countLongVoiding_isZero_whenNone() {
        // given
        insert("COMPENSATING", ALERT_ATTEMPTS - 1, now.minusSeconds(100));

        // when, then
        assertThat(subscriptionRepository.countLongVoiding(now, ALERT_ATTEMPTS, MIN_AGE)).isZero();
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
