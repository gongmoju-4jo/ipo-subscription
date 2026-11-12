package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.application.subscription.VoidRetryResult.Outcome;
import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * 반환 재요청 1건(재요청, 건너뛰기, 요청 횟수별 대기 시간)을 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("COMPENSATING, CANCEL_REQUESTED 반환 재요청 (PostgreSQL)")
class SubscriptionVoidRetryServiceTest {

    // 기본 설정과 같은 값 (요청 횟수 1, 2, 3, 4, 5 이상일 때 30초, 60초, 120초, 240초, 300초)
    private static final VoidRetryBackoff BACKOFF = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(5));
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionVoidRetryService voidRetryService;

    @MockitoSpyBean
    private MarginVoidRequestRecorder voidRequestRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID accountId;
    private UUID subscriptionId;
    // 재요청 판단 기준 시각 (대기 시간이 지났는지는 이 시각에서 대기 시간을 뺀 값과 마지막 요청 시각을 비교)
    private Instant now;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        // PostgreSQL은 시각을 마이크로초로 저장하므로 기준 시각을 같은 정밀도로 맞춤 (아니면 "기준과 같은 경우" 경계 테스트가 간헐적으로 실패함)
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        // 요청 횟수 1의 대기 시간(30초)보다 10초 더 지남
        insertSubscription("COMPENSATING", "TIMEOUT", 1, now.minusSeconds(40));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("대기 시간이 지난 COMPENSATING은 요청 횟수가 1 늘고 마지막 요청 시각이 갱신되며 상태와 해제 사유는 그대로이고, 반환 요청 Outbox가 처음과 같은 내용으로 1건 다시 기록된다")
    void handle_retriesVoid_whenWaitElapsed() {
        // given
        Instant before = Instant.now();

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.RETRIED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("cancel_reason")).isEqualTo("TIMEOUT");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(((Timestamp) row.get("last_requested_at")).toInstant()).isAfterOrEqualTo(before.minusMillis(1));
        assertThat(row.get("version")).isEqualTo(1L);

        List<Map<String, Object>> outbox = outbox("margin.void-requested");
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("message_key")).isEqualTo(accountId.toString());
        assertThat(outbox.get(0).get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(outbox.get(0).get("account_id")).isEqualTo(accountId.toString());
        assertThat(outbox.get(0).get("reason")).isEqualTo("TIMEOUT");
        assertThat(outbox("margin.reserve-requested")).isEmpty();
    }

    @ParameterizedTest(name = "{0}, {1} → 해제 사유 {2}")
    @CsvSource({"COMPENSATING, CONFIRM_FAILED", "COMPENSATING, TIMEOUT", "CANCEL_REQUESTED, USER_CANCEL"})
    @DisplayName("재요청은 상태와 관계없이 청약의 해제 사유를 그대로 반환 요청의 reason으로 쓴다")
    void handle_usesSameCancelReason(String status, String cancelReason) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = ? WHERE subscription_id = ?", status, cancelReason, subscriptionId);

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.RETRIED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("cancel_reason")).isEqualTo(cancelReason);
        List<Map<String, Object>> outbox = outbox("margin.void-requested");
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).get("reason")).isEqualTo(cancelReason);
    }

    @Test
    @DisplayName("재요청마다 Outbox 행이 새로 생긴다 (eventId가 되는 outbox_id가 서로 다름)")
    void handle_recordsNewOutboxRowEachRetry() {
        // given
        voidRetryService.handle(subscriptionId, now, BACKOFF);
        // 요청 횟수 2의 대기 시간(60초)보다 10초 더 지남
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                Timestamp.from(now.minusSeconds(70)), subscriptionId);

        // when
        VoidRetryResult second = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(second.outcome()).isEqualTo(Outcome.RETRIED);
        assertThat(subscription().get("attempts")).isEqualTo(3);
        List<UUID> ids = jdbcTemplate.queryForList("SELECT outbox_id FROM outbox WHERE aggregate_id = ? AND topic = 'margin.void-requested'", UUID.class, subscriptionId);
        assertThat(ids).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("재요청 직후에는 마지막 요청 시각이 새로워서 같은 기준 시각으로 다시 처리해도 건너뛴다")
    void handle_ignoresRightAfterRetry() {
        // given
        voidRetryService.handle(subscriptionId, now, BACKOFF);

        // when
        VoidRetryResult again = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(again.outcome()).isEqualTo(Outcome.NOT_TIMED_OUT_IGNORED);
        assertThat(subscription().get("attempts")).isEqualTo(2);
        assertThat(outbox("margin.void-requested")).hasSize(1);
    }

    @ParameterizedTest(name = "요청 횟수 {0}")
    @ValueSource(ints = {3, 4, 5, 6, 10, 20, 50})
    @DisplayName("요청 횟수가 3 이상이어도(이전 최대 횟수 이후) 대기 시간이 지났으면 요청 횟수가 1 늘고 반환을 계속 재요청한다")
    void handle_retries_whenAttemptsAtOrAboveFormerMax(int attempts) {
        // given
        Instant lastRequestedAt = now.minus(BACKOFF.waitAfter(attempts)).minusSeconds(10);
        jdbcTemplate.update("UPDATE subscription SET attempts = ?, last_requested_at = ? WHERE subscription_id = ?",
                attempts, Timestamp.from(lastRequestedAt), subscriptionId);

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.RETRIED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("attempts")).isEqualTo(attempts + 1);
        assertThat(((Timestamp) row.get("last_requested_at")).toInstant()).isAfter(lastRequestedAt);
        assertThat(outbox("margin.void-requested")).hasSize(1);
    }

    @ParameterizedTest(name = "요청 횟수 {0} → 대기 {1}초")
    @CsvSource({"1, 30", "2, 60", "3, 120", "4, 240", "5, 300", "6, 300", "30, 300"})
    @DisplayName("마지막 요청 뒤 대기 시간이 아직 지나지 않았거나 정확히 같으면 요청 횟수별 대기 시간(30초에서 시작해 2배, 5분 상한)대로 건너뛴다")
    void handle_ignores_whenWaitNotElapsed(int attempts, long waitSeconds) {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ? WHERE subscription_id = ?", attempts, subscriptionId);

        for (long secondsAgo : new long[]{waitSeconds - 1, waitSeconds}) {
            jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                    Timestamp.from(now.minusSeconds(secondsAgo)), subscriptionId);

            // when
            VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

            // then
            assertThat(result.outcome()).isEqualTo(Outcome.NOT_TIMED_OUT_IGNORED);
            assertThat(subscription().get("attempts")).isEqualTo(attempts);
            assertThat(outbox("margin.void-requested")).isEmpty();
        }
    }

    @ParameterizedTest(name = "요청 횟수 {0} → 대기 {1}초")
    @CsvSource({"1, 30", "2, 60", "3, 120", "4, 240", "5, 300", "6, 300", "30, 300"})
    @DisplayName("마지막 요청 뒤 요청 횟수별 대기 시간보다 1초 더 지났으면 재요청한다")
    void handle_retries_whenWaitJustElapsed(int attempts, long waitSeconds) {
        // given
        jdbcTemplate.update("UPDATE subscription SET attempts = ?, last_requested_at = ? WHERE subscription_id = ?",
                attempts, Timestamp.from(now.minusSeconds(waitSeconds + 1)), subscriptionId);

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.RETRIED);
        assertThat(subscription().get("attempts")).isEqualTo(attempts + 1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PENDING", "CONFIRMED", "REJECTED", "CANCELLED", "ALLOCATED", "REFUNDED"})
    @DisplayName("COMPENSATING, CANCEL_REQUESTED가 아닌 청약(응답이 와서 CANCELLED가 된 경우 등)은 아무것도 바꾸지 않고 건너뛴다")
    void handle_ignores_whenNotVoiding(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_VOIDING_IGNORED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(outbox("margin.void-requested")).isEmpty();
    }

    @Test
    @DisplayName("마지막 요청 시각이 기준 시각 이후이면(다른 인스턴스가 이미 재요청) 아무것도 바꾸지 않고 건너뛴다")
    void handle_ignores_whenLastRequestedAfterNow() {
        // given
        jdbcTemplate.update("UPDATE subscription SET last_requested_at = ? WHERE subscription_id = ?",
                Timestamp.from(now.plusSeconds(5)), subscriptionId);

        // when
        VoidRetryResult result = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(result.outcome()).isEqualTo(Outcome.NOT_TIMED_OUT_IGNORED);
        assertThat(subscription().get("attempts")).isEqualTo(1);
        assertThat(outbox("margin.void-requested")).isEmpty();
    }

    @Test
    @DisplayName("없는 청약 ID로 처리하면 IllegalStateException이 난다")
    void handle_throws_whenSubscriptionMissing() {
        // given
        UUID unknown = UUID.randomUUID();

        // when, then
        assertThatThrownBy(() -> voidRetryService.handle(unknown, now, BACKOFF))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("반환 요청 Outbox 기록이 실패하면 요청 횟수와 요청 시각 변경도 함께 롤백된다")
    void handle_rollsBack_whenOutboxRecordFails() {
        // given
        Object lastRequestedAtBefore = subscription().get("last_requested_at");
        doThrow(new IllegalStateException("Outbox 실패")).when(voidRequestRecorder).record(any());

        // when, then
        assertThatThrownBy(() -> voidRetryService.handle(subscriptionId, now, BACKOFF))
                .isInstanceOf(IllegalStateException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("last_requested_at")).isEqualTo(lastRequestedAtBefore);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(outbox("margin.void-requested")).isEmpty();
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
            List<Future<VoidRetryResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return voidRetryService.handle(subscriptionId, now, BACKOFF);
                }));
            }
            int retried = 0;
            for (Future<VoidRetryResult> future : futures) {
                try {
                    if (future.get(30, TimeUnit.SECONDS).outcome() == Outcome.RETRIED) {
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
            assertThat(outbox("margin.void-requested")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private void insertSubscription(String status, String cancelReason, int attempts, Instant lastRequestedAt) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, MARGIN_AMOUNT, status, cancelReason,
                attempts, Timestamp.from(lastRequestedAt));
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, attempts, last_requested_at, version
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private List<Map<String, Object>> outbox(String topic) {
        return jdbcTemplate.queryForList("""
                SELECT message_key, payload->>'subscriptionId' AS subscription_id,
                       payload->>'accountId' AS account_id, payload->>'reason' AS reason
                FROM outbox WHERE aggregate_id = ? AND topic = ? ORDER BY created_at""", subscriptionId, topic);
    }
}
