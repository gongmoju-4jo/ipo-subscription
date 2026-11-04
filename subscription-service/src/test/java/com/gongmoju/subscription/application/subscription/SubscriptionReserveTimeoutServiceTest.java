package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * PENDING 응답 시간 초과 처리 1건(재요청, 시간 초과 보상, 건너뛰기)을 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("PENDING 응답 시간 초과 처리 (PostgreSQL)")
class SubscriptionReserveTimeoutServiceTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionReserveTimeoutService timeoutService;

    @MockitoSpyBean
    private MarginReserveRequestRecorder reserveRequestRecorder;

    @MockitoSpyBean
    private MarginVoidRequestRecorder voidRequestRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID userId;
    private UUID accountId;
    private UUID subscriptionId;
    // 응답 시간이 지났는지 가르는 기준 시각 (현재 시각 - 30초)
    private Instant threshold;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        threshold = Instant.now().minusSeconds(30);
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        insertSubscription("PENDING", 1, threshold.minusSeconds(10));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("응답 시간이 지난 PENDING은 요청 횟수가 1 늘고 마지막 요청 시각이 갱신되며, 인출 요청 Outbox가 처음과 같은 내용으로 1건 다시 기록된다")
    void handle_retriesReserve_whenAttemptsBelowMax() {
        // given
        Instant before = Instant.now();

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.RETRIED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(((Timestamp) row.get("last_requested_at")).toInstant()).isAfterOrEqualTo(before.minusMillis(1));
        assertThat(row.get("version")).isEqualTo(1L);

        List<Map<String, Object>> outbox = outbox("margin.reserve-requested");
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("message_key")).isEqualTo(accountId.toString());
        assertThat(outbox.get(0).get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(outbox.get(0).get("user_id")).isEqualTo(userId.toString());
        assertThat(outbox.get(0).get("account_id")).isEqualTo(accountId.toString());
        assertThat(outbox.get(0).get("ipo_id")).isEqualTo(ipoId.toString());
        assertThat(outbox.get(0).get("amount")).isEqualTo(MARGIN_AMOUNT);
        assertThat(outbox("margin.void-requested")).isEmpty();
    }

    @Test
    @DisplayName("재요청마다 Outbox 행이 새로 생긴다 (eventId가 되는 outbox_id가 서로 다름)")
    void handle_recordsNewOutboxRowEachRetry() {
        // given
        timeoutService.handle(subscriptionId, threshold, 10);
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                Timestamp.from(threshold.minusSeconds(10)), subscriptionId);

        // when
        ReserveTimeoutResult second = timeoutService.handle(subscriptionId, threshold, 10);

        // then
        assertThat(second).isEqualTo(ReserveTimeoutResult.RETRIED);
        assertThat(subscription().get("attempts")).isEqualTo(3);
        List<UUID> outboxIds = jdbcTemplate.queryForList(
                "SELECT outbox_id FROM outbox WHERE aggregate_id = ? AND topic = 'margin.reserve-requested'", UUID.class, subscriptionId);
        assertThat(outboxIds).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("최대 횟수 경계: 요청 횟수 2는 재요청하고, 3은 COMPENSATING(TIMEOUT)으로 보상한다")
    void handle_boundaryAtMaxAttempts() {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = 2 WHERE subscription_id = ?", subscriptionId);

        // when
        ReserveTimeoutResult atTwo = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(atTwo).isEqualTo(ReserveTimeoutResult.RETRIED);
        assertThat(subscription().get("attempts")).isEqualTo(3);

        // given
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                Timestamp.from(threshold.minusSeconds(10)), subscriptionId);

        // when
        ReserveTimeoutResult atThree = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(atThree).isEqualTo(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED);
        assertThat(subscription().get("status")).isEqualTo("COMPENSATING");
    }

    @Test
    @DisplayName("최대 횟수에 이른 PENDING은 COMPENSATING(TIMEOUT)이 되고 margin_id는 비며 요청 횟수는 반환 요청 1회로 바뀌고 반환 요청 Outbox만 1건 기록된다")
    void handle_startsTimeoutCompensation_whenAttemptsReachedMax() {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ? WHERE subscription_id = ?", MAX_ATTEMPTS, subscriptionId);

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("cancel_reason")).isEqualTo("TIMEOUT");
        assertThat(row.get("margin_id")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("version")).isEqualTo(1L);

        List<Map<String, Object>> voidRequests = outbox("margin.void-requested");
        assertThat(voidRequests).hasSize(1);
        assertThat(voidRequests.get(0).get("message_key")).isEqualTo(accountId.toString());
        assertThat(voidRequests.get(0).get("reason")).isEqualTo("TIMEOUT");
        assertThat(outbox("margin.reserve-requested")).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONFIRMED", "REJECTED", "COMPENSATING", "CANCELLED"})
    @DisplayName("그 사이 PENDING이 아니게 된 청약은 아무것도 바꾸지 않고 Outbox도 기록하지 않는다")
    void handle_ignores_whenNotPending(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.NOT_PENDING_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("attempts")).isEqualTo(1);
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(outbox("margin.reserve-requested")).isEmpty();
        assertThat(outbox("margin.void-requested")).isEmpty();
    }

    @Test
    @DisplayName("그 사이 요청 시각이 갱신되어 아직 응답 시간이 지나지 않았으면 아무것도 바꾸지 않는다")
    void handle_ignores_whenAlreadyRequestedAgain() {
        // given
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = now() WHERE subscription_id = ?", subscriptionId);

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.NOT_TIMED_OUT_IGNORED);
        assertThat(subscription().get("attempts")).isEqualTo(1);
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(outbox("margin.reserve-requested")).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 청약이면 IllegalStateException이 난다")
    void handle_throws_whenSubscriptionNotFound() {
        // given
        UUID unknown = UUID.randomUUID();

        // when, then
        assertThatThrownBy(() -> timeoutService.handle(unknown, threshold, MAX_ATTEMPTS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Outbox 기록이 실패하면 요청 횟수와 요청 시각 변경도 함께 롤백된다")
    void handle_rollsBack_whenOutboxRecordFails() {
        // given
        doThrow(new IllegalStateException("Outbox 실패")).when(reserveRequestRecorder).record(any());

        // when, then
        assertThatThrownBy(() -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subscription().get("attempts")).isEqualTo(1);
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(outbox("margin.reserve-requested")).isEmpty();
    }

    @Test
    @DisplayName("반환 요청 기록이 실패하면 COMPENSATING(TIMEOUT) 전환, 요청 횟수, 요청 시각 변경도 함께 롤백되어 PENDING으로 남고 Outbox는 비어 있다")
    void handle_rollsBackTimeoutCompensation_whenVoidRequestRecordFails() {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ? WHERE subscription_id = ?", MAX_ATTEMPTS, subscriptionId);
        Object lastRequestedAtBefore = subscription().get("last_requested_at");
        doThrow(new IllegalStateException("반환 요청 Outbox 실패")).when(voidRequestRecorder).record(any());

        // when, then
        assertThatThrownBy(() -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS))
                .isInstanceOf(IllegalStateException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(row.get("attempts")).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.get("last_requested_at")).isEqualTo(lastRequestedAtBefore);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(outbox("margin.void-requested")).isEmpty();
        assertThat(outbox("margin.reserve-requested")).isEmpty();
    }

    @Test
    @DisplayName("최대 횟수에 이른 PENDING이라도 마지막 인출 요청이 방금 발행되어 응답 시간이 지나지 않았으면 TIMEOUT 보상을 하지 않고 기다린다")
    void handle_defersTimeoutCompensation_whenLastReserveRequestWasPublishedRecently() {
        // given
        // 기록은 60초 전(응답 시간이 지남)이지만 발행이 밀려 5초 전에야 나감
        jdbcTemplate.update("UPDATE subscription SET attempts = ?, last_requested_at = ? WHERE subscription_id = ?",
                MAX_ATTEMPTS, Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);
        insertReserveRequestOutbox(Instant.now().minusSeconds(60), Instant.now().minusSeconds(5));

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.NOT_TIMED_OUT_IGNORED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(MAX_ATTEMPTS);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(outbox("margin.void-requested")).isEmpty();
    }

    @Test
    @DisplayName("마지막 인출 요청이 발행된 지 응답 시간이 지났으면 TIMEOUT 보상을 시작한다")
    void handle_startsTimeoutCompensation_whenLastReserveRequestWasPublishedLongAgo() {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ?, last_requested_at = ? WHERE subscription_id = ?",
                MAX_ATTEMPTS, Timestamp.from(Instant.now().minusSeconds(120)), subscriptionId);
        insertReserveRequestOutbox(Instant.now().minusSeconds(120), Instant.now().minusSeconds(60));

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED);
        assertThat(subscription().get("status")).isEqualTo("COMPENSATING");
        assertThat(outbox("margin.void-requested")).hasSize(1);
    }

    @Test
    @DisplayName("최대 횟수에 이르지 않은 PENDING은 마지막 요청이 방금 발행됐어도 재요청한다 (계좌가 같은 subscriptionId의 재요청을 멱등 처리하므로 해가 없음)")
    void handle_retriesReserve_evenWhenLastReserveRequestWasPublishedRecently() {
        // given
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), subscriptionId);
        insertReserveRequestOutbox(Instant.now().minusSeconds(60), Instant.now().minusSeconds(5));

        // when
        ReserveTimeoutResult result = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(result).isEqualTo(ReserveTimeoutResult.RETRIED);
        assertThat(subscription().get("attempts")).isEqualTo(2);
    }

    @Test
    @DisplayName("TIMEOUT 보상 뒤에 같은 청약을 다시 처리해도 인출 요청이나 반환 요청이 더 기록되지 않는다")
    void handle_recordsNothingMore_afterTimeoutCompensation() {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ? WHERE subscription_id = ?", MAX_ATTEMPTS, subscriptionId);
        timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // when
        ReserveTimeoutResult again = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then
        assertThat(again).isEqualTo(ReserveTimeoutResult.NOT_PENDING_IGNORED);
        assertThat(outbox("margin.reserve-requested")).isEmpty();
        assertThat(outbox("margin.void-requested")).hasSize(1);
        assertThat(subscription().get("status")).isEqualTo("COMPENSATING");
    }

    @Test
    @DisplayName("같은 청약을 동시에 처리하면 한 번만 재요청되고 나머지는 건너뛰거나 낙관적 락 충돌로 끝난다")
    void handle_retriesOnce_whenConcurrent() throws Exception {
        // given
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<ReserveTimeoutResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return timeoutService.handle(subscriptionId, threshold, 10);
                }));
            }
            int retried = 0;
            for (Future<ReserveTimeoutResult> future : futures) {
                try {
                    if (future.get(30, TimeUnit.SECONDS) == ReserveTimeoutResult.RETRIED) {
                        retried++;
                    }
                } catch (ExecutionException e) {
                    // 낙관적 락 충돌은 스케줄러가 건너뛰고 다음 주기에 다시 판단하는 예외라 허용함
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }

            // then
            assertThat(retried).isEqualTo(1);
            assertThat(subscription().get("attempts")).isEqualTo(2);
            assertThat(outbox("margin.reserve-requested")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertSubscription(String status, int attempts, Instant lastRequestedAt) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, userId, accountId, MARGIN_AMOUNT, status, attempts,
                Timestamp.from(lastRequestedAt));
    }

    // 이미 발행된 인출 요청 Outbox 행을 만든다. 기록(created_at)이 발행(published_at)보다 앞서는 실제 순서대로 넣음
    private void insertReserveRequestOutbox(Instant createdAt, Instant publishedAt) {
        jdbcTemplate.update("""
                INSERT INTO outbox (outbox_id, aggregate_type, aggregate_id, event_type, topic, message_key,
                                    payload, created_at, published_at, retry_count)
                VALUES (?, 'SUBSCRIPTION', ?, 'margin.reserve-requested', 'margin.reserve-requested', ?, CAST('{}' AS JSONB), ?, ?, 0)""",
                UUID.randomUUID(), subscriptionId, accountId.toString(), Timestamp.from(createdAt), Timestamp.from(publishedAt));
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, margin_id, attempts, last_requested_at, version
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private List<Map<String, Object>> outbox(String topic) {
        return jdbcTemplate.queryForList("""
                SELECT message_key, payload->>'subscriptionId' AS subscription_id, payload->>'userId' AS user_id,
                       payload->>'accountId' AS account_id, payload->>'ipoId' AS ipo_id,
                       (payload->>'amount')::bigint AS amount, payload->>'reason' AS reason
                FROM outbox WHERE aggregate_id = ? AND topic = ? ORDER BY created_at""", subscriptionId, topic);
    }
}
