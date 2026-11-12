package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserved;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증거금 확보 성공 응답 처리(처리 기록, 청약 확정)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 확정 처리 (PostgreSQL)")
class SubscriptionConfirmServiceTest {

    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionConfirmService confirmService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID subscriptionId;
    private Instant reservedAt;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        reservedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        insertSubscription(subscriptionId, "PENDING", 1);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionConfirmService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("PENDING 청약은 CONFIRMED가 되고 증거금 ID, 확정 시각이 저장되며 요청 횟수가 0이 되고 처리 기록이 남는다")
    void confirm_changesPendingToConfirmed() {
        // given
        UUID marginId = UUID.randomUUID();
        MarginReserved event = event(UUID.randomUUID(), marginId, MARGIN_AMOUNT);

        // when
        ConfirmResult result = confirmService.confirm(event);

        // then
        assertThat(result).isEqualTo(ConfirmResult.CONFIRMED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("margin_id")).isEqualTo(marginId);
        assertThat(((java.sql.Timestamp) row.get("confirmed_at")).toInstant()).isEqualTo(reservedAt);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 상태를 바꾸지 않고 DUPLICATE_EVENT_IGNORED로 끝나며 처리 기록은 1개다")
    void confirm_ignores_whenSameEventIdArrivesAgain() {
        // given
        MarginReserved event = event(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT);
        confirmService.confirm(event);
        Object versionAfterFirst = subscription().get("version");

        // when
        ConfirmResult result = confirmService.confirm(event);

        // then
        assertThat(result).isEqualTo(ConfirmResult.DUPLICATE_EVENT_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(versionAfterFirst);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("eventId가 달라도 이미 CONFIRMED인 청약이면 ALREADY_CONFIRMED_IGNORED로 끝나고 처음 증거금 ID가 유지된다")
    void confirm_ignores_whenAlreadyConfirmedWithNewEventId() {
        // given
        UUID firstMarginId = UUID.randomUUID();
        confirmService.confirm(event(UUID.randomUUID(), firstMarginId, MARGIN_AMOUNT));

        // when
        ConfirmResult result = confirmService.confirm(event(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        assertThat(result).isEqualTo(ConfirmResult.ALREADY_CONFIRMED_IGNORED);
        assertThat(subscription().get("margin_id")).isEqualTo(firstMarginId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CANCEL_REQUESTED", "ALLOCATED", "REFUNDED"})
    @DisplayName("확정 이후 상태(CANCEL_REQUESTED, ALLOCATED, REFUNDED)에서 받으면 상태를 바꾸지 않고 ALREADY_CONFIRMED_IGNORED로 끝난다")
    void confirm_ignores_whenStatusIsAfterConfirmed(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        ConfirmResult result = confirmService.confirm(event(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        assertThat(result).isEqualTo(ConfirmResult.ALREADY_CONFIRMED_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("margin_id")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"COMPENSATING", "CANCELLED", "REJECTED"})
    @DisplayName("정상적으로는 오지 않는 상태(COMPENSATING, CANCELLED, REJECTED)에서 받으면 상태를 바꾸지 않고 UNEXPECTED_STATE_IGNORED로 끝난다")
    void confirm_ignoresWithErrorLog_whenStatusIsUnexpected(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        ConfirmResult result = confirmService.confirm(event(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        assertThat(result).isEqualTo(ConfirmResult.UNEXPECTED_STATE_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("margin_id")).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 InvalidEventException이 나고 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void confirm_throwsAndRollsBackProcessedEvent_whenSubscriptionNotFound() {
        // given
        MarginReserved event = new MarginReserved(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, reservedAt);

        // when, then
        assertThatThrownBy(() -> confirmService.confirm(event)).isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @Test
    @DisplayName("확보 금액이 청약의 증거금과 달라도 검증하지 않고 확정한다")
    void confirm_confirms_whenAmountDiffers() {
        // given
        MarginReserved event = event(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT + 1);

        // when
        ConfirmResult result = confirmService.confirm(event);

        // then
        assertThat(result).isEqualTo(ConfirmResult.CONFIRMED);
        assertThat(subscription().get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("같은 청약에 서로 다른 eventId의 응답이 동시에 오면 한 번만 확정되고 나머지는 예외 없이 무시되거나 재시도 대상 예외로 끝난다")
    void confirm_confirmsOnce_whenConcurrent() throws Exception {
        // given
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        UUID marginId = UUID.randomUUID();

        try {
            // when
            List<Future<ConfirmResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                MarginReserved event = event(UUID.randomUUID(), marginId, MARGIN_AMOUNT);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return confirmService.confirm(event);
                }));
            }
            int confirmed = 0;
            for (Future<ConfirmResult> future : futures) {
                try {
                    if (future.get(30, TimeUnit.SECONDS) == ConfirmResult.CONFIRMED) {
                        confirmed++;
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    // 낙관적 락 충돌은 Consumer의 에러 핸들러가 재시도하는 예외라 허용함 (재시도하면 CONFIRMED 이후로 무시됨)
                    assertThat(e.getCause()).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
                }
            }

            // then
            assertThat(confirmed).isEqualTo(1);
            assertThat(subscription().get("status")).isEqualTo("CONFIRMED");
            assertThat(subscription().get("margin_id")).isEqualTo(marginId);
        } finally {
            executor.shutdownNow();
        }
    }

    private MarginReserved event(UUID eventId, UUID marginId, long amount) {
        return new MarginReserved(eventId, subscriptionId, marginId, amount, reservedAt);
    }

    private void insertSubscription(UUID id, String status, int attempts) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, now(), now() - interval '1 minute',
                        0, now(), 'test', now(), 'test')""",
                id, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, status, attempts);
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, margin_id, confirmed_at, attempts, version FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
