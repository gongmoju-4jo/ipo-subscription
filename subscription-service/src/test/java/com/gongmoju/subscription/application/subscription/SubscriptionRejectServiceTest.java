package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.RejectReason;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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

/**
 * 증거금 확보 실패 응답 처리(처리 기록, 청약 거절)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 거절 처리 (PostgreSQL)")
class SubscriptionRejectServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRejectService rejectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 접수 Consumer만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, 'PENDING', 1, now(), now() - interval '1 minute',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionRejectService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INSUFFICIENT_BALANCE", "ACCOUNT_NOT_FOUND"})
    @DisplayName("PENDING 청약은 REJECTED가 되고 거절 사유가 저장되며 요청 횟수가 0이 되고 처리 기록이 남으며 Outbox는 기록되지 않는다")
    void reject_changesPendingToRejected(String reason) {
        // given
        MarginReserveFailed event = event(UUID.randomUUID(), RejectReason.valueOf(reason));

        // when
        ReserveFailedResult result = rejectService.reject(event);

        // then
        assertThat(result).isEqualTo(ReserveFailedResult.REJECTED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("reject_reason")).isEqualTo(reason);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 상태를 바꾸지 않고 DUPLICATE_EVENT_IGNORED로 끝나며 처리 기록은 1개다")
    void reject_ignores_whenSameEventIdArrivesAgain() {
        // given
        MarginReserveFailed event = event(UUID.randomUUID(), RejectReason.INSUFFICIENT_BALANCE);
        rejectService.reject(event);
        Object versionAfterFirst = subscription().get("version");

        // when
        ReserveFailedResult result = rejectService.reject(event);

        // then
        assertThat(result).isEqualTo(ReserveFailedResult.DUPLICATE_EVENT_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(versionAfterFirst);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("eventId가 달라도 이미 REJECTED인 청약이면 ALREADY_REJECTED_IGNORED로 끝나고 처음 거절 사유가 유지된다")
    void reject_ignores_whenAlreadyRejectedWithNewEventId() {
        // given
        rejectService.reject(event(UUID.randomUUID(), RejectReason.INSUFFICIENT_BALANCE));

        // when
        ReserveFailedResult result = rejectService.reject(event(UUID.randomUUID(), RejectReason.ACCOUNT_NOT_FOUND));

        // then
        assertThat(result).isEqualTo(ReserveFailedResult.ALREADY_REJECTED_IGNORED);
        assertThat(subscription().get("reject_reason")).isEqualTo("INSUFFICIENT_BALANCE");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONFIRMED", "COMPENSATING", "CANCEL_REQUESTED", "CANCELLED", "ALLOCATED", "REFUNDED"})
    @DisplayName("PENDING, REJECTED 외 상태에서 받으면 상태를 바꾸지 않고 UNEXPECTED_STATE_IGNORED로 끝난다")
    void reject_ignoresWithErrorLog_whenStatusIsUnexpected(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        ReserveFailedResult result = rejectService.reject(event(UUID.randomUUID(), RejectReason.INSUFFICIENT_BALANCE));

        // then
        assertThat(result).isEqualTo(ReserveFailedResult.UNEXPECTED_STATE_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("reject_reason")).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 InvalidEventException이 나고 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void reject_throwsAndRollsBackProcessedEvent_whenSubscriptionNotFound() {
        // given
        MarginReserveFailed event = new MarginReserveFailed(UUID.randomUUID(), UUID.randomUUID(), RejectReason.INSUFFICIENT_BALANCE);

        // when, then
        assertThatThrownBy(() -> rejectService.reject(event)).isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @Test
    @DisplayName("같은 청약에 서로 다른 eventId의 응답이 동시에 오면 한 번만 거절되고 나머지는 무시되거나 재시도 대상 예외로 끝난다")
    void reject_rejectsOnce_whenConcurrent() throws Exception {
        // given
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<ReserveFailedResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                MarginReserveFailed event = event(UUID.randomUUID(), RejectReason.INSUFFICIENT_BALANCE);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return rejectService.reject(event);
                }));
            }
            int rejected = 0;
            for (Future<ReserveFailedResult> future : futures) {
                try {
                    if (future.get(30, TimeUnit.SECONDS) == ReserveFailedResult.REJECTED) {
                        rejected++;
                    }
                } catch (ExecutionException e) {
                    // 낙관적 락 충돌은 Consumer의 에러 핸들러가 재시도하는 예외라 허용함 (재시도하면 REJECTED 이후로 무시됨)
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }

            // then
            assertThat(rejected).isEqualTo(1);
            assertThat(subscription().get("status")).isEqualTo("REJECTED");
        } finally {
            executor.shutdownNow();
        }
    }

    private MarginReserveFailed event(UUID eventId, RejectReason reason) {
        return new MarginReserveFailed(eventId, subscriptionId, reason);
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, reject_reason, attempts, version FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, subscriptionId);
    }
}
