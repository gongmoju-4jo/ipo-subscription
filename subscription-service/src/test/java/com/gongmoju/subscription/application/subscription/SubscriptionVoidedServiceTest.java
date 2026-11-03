package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginVoided;
import com.gongmoju.subscription.global.exception.InvalidEventException;
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
import org.springframework.dao.DuplicateKeyException;
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
 * 증거금 해제 완료 응답 처리(처리 기록, 청약 취소 완료)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 취소 완료 처리 (PostgreSQL)")
class SubscriptionVoidedServiceTest {

    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionVoidedService voidedService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID userId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 접수 Consumer만 사용) SQL로 직접 저장함. 청약은 보상이 시작된 {@code COMPENSATING(CONFIRM_FAILED)} 상태로 만듦.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        insertSubscription(subscriptionId, userId, "COMPENSATING", "CONFIRM_FAILED", 1);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionVoidedService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "{0} ({1}), 해제 금액 {2}")
    @CsvSource({
            "COMPENSATING, CONFIRM_FAILED, 1500000",
            "COMPENSATING, TIMEOUT, 1500000",
            "COMPENSATING, TIMEOUT, 0",
            "CANCEL_REQUESTED, USER_CANCEL, 1500000"
    })
    @DisplayName("COMPENSATING, CANCEL_REQUESTED 청약은 CANCELLED가 되고 해제 사유가 유지되며 요청 횟수가 0이 되고 처리 기록이 남으며 Outbox는 기록되지 않는다")
    void complete_changesToCancelled(String status, String cancelReason, long amount) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = ?, attempts = 2 WHERE subscription_id = ?",
                status, cancelReason, subscriptionId);
        MarginVoided event = event(UUID.randomUUID(), amount);

        // when
        VoidedResult result = voidedService.complete(event);

        // then
        assertThat(result).isEqualTo(VoidedResult.CANCELLED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("cancel_reason")).isEqualTo(cancelReason);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("해제 금액이 청약의 증거금과 달라도 에러 로그만 남기고 CANCELLED로 전환한다")
    void complete_changesToCancelled_whenAmountDiffers() {
        // given
        MarginVoided event = event(UUID.randomUUID(), MARGIN_AMOUNT + 1);

        // when
        VoidedResult result = voidedService.complete(event);

        // then
        assertThat(result).isEqualTo(VoidedResult.CANCELLED);
        assertThat(subscription().get("status")).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 상태를 바꾸지 않고 DUPLICATE_EVENT_IGNORED로 끝나며 처리 기록은 1개다")
    void complete_ignores_whenSameEventIdArrivesAgain() {
        // given
        MarginVoided event = event(UUID.randomUUID(), MARGIN_AMOUNT);
        voidedService.complete(event);
        Object versionAfterFirst = subscription().get("version");

        // when
        VoidedResult result = voidedService.complete(event);

        // then
        assertThat(result).isEqualTo(VoidedResult.DUPLICATE_EVENT_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(versionAfterFirst);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("eventId가 달라도 이미 CANCELLED인 청약이면 ALREADY_CANCELLED_IGNORED로 끝나고 상태와 버전이 유지된다")
    void complete_ignores_whenAlreadyCancelledWithNewEventId() {
        // given
        voidedService.complete(event(UUID.randomUUID(), MARGIN_AMOUNT));
        Object versionAfterFirst = subscription().get("version");

        // when
        VoidedResult result = voidedService.complete(event(UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        assertThat(result).isEqualTo(VoidedResult.ALREADY_CANCELLED_IGNORED);
        assertThat(subscription().get("status")).isEqualTo("CANCELLED");
        assertThat(subscription().get("version")).isEqualTo(versionAfterFirst);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PENDING", "CONFIRMED", "REJECTED", "ALLOCATED", "REFUNDED"})
    @DisplayName("COMPENSATING, CANCEL_REQUESTED, CANCELLED 외 상태에서 받으면 상태를 바꾸지 않고 UNEXPECTED_STATE_IGNORED로 끝난다")
    void complete_ignoresWithErrorLog_whenStatusIsUnexpected(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = NULL WHERE subscription_id = ?", status, subscriptionId);

        // when
        VoidedResult result = voidedService.complete(event(UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        assertThat(result).isEqualTo(VoidedResult.UNEXPECTED_STATE_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("version")).isEqualTo(0L);
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 InvalidEventException이 나고 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void complete_throwsAndRollsBackProcessedEvent_whenSubscriptionNotFound() {
        // given
        MarginVoided event = new MarginVoided(UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT);

        // when, then
        assertThatThrownBy(() -> voidedService.complete(event)).isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @Test
    @DisplayName("해제 사유가 없는 COMPENSATING 청약이면 IllegalStateException이 나고 처리 기록도 남지 않는다 (재시도 대상)")
    void complete_throwsAndRollsBackProcessedEvent_whenCancelReasonIsMissing() {
        // given
        jdbcTemplate.update("UPDATE subscription SET cancel_reason = NULL WHERE subscription_id = ?", subscriptionId);
        MarginVoided event = event(UUID.randomUUID(), MARGIN_AMOUNT);

        // when, then
        assertThatThrownBy(() -> voidedService.complete(event)).isInstanceOf(IllegalStateException.class);
        assertThat(processedCount(event.eventId())).isZero();
        assertThat(subscription().get("status")).isEqualTo("COMPENSATING");
    }

    @Test
    @DisplayName("같은 청약에 서로 다른 eventId의 응답이 동시에 오면 한 번만 취소되고 나머지는 무시되거나 재시도 대상 예외로 끝난다")
    void complete_cancelsOnce_whenConcurrent() throws Exception {
        // given
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<VoidedResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                MarginVoided event = event(UUID.randomUUID(), MARGIN_AMOUNT);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return voidedService.complete(event);
                }));
            }
            int cancelled = 0;
            for (Future<VoidedResult> future : futures) {
                try {
                    if (future.get(30, TimeUnit.SECONDS) == VoidedResult.CANCELLED) {
                        cancelled++;
                    }
                } catch (ExecutionException e) {
                    // 낙관적 락 충돌은 Consumer의 에러 핸들러가 재시도하는 예외라 허용함 (재시도하면 CANCELLED 이후로 무시됨)
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }

            // then
            assertThat(cancelled).isEqualTo(1);
            assertThat(subscription().get("status")).isEqualTo("CANCELLED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 eventId의 응답을 동시에 처리해도 청약 전환과 처리 기록은 한 번만 남는다")
    void complete_cancelsOnce_whenSameEventIsConcurrent() throws Exception {
        UUID eventId = UUID.randomUUID();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            List<Future<VoidedResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return voidedService.complete(event(eventId, MARGIN_AMOUNT));
                }));
            }

            List<VoidedResult> results = new ArrayList<>();
            for (Future<VoidedResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder(
                    VoidedResult.CANCELLED,
                    VoidedResult.DUPLICATE_EVENT_IGNORED,
                    VoidedResult.DUPLICATE_EVENT_IGNORED,
                    VoidedResult.DUPLICATE_EVENT_IGNORED);
            assertThat(subscription().get("status")).isEqualTo("CANCELLED");
            assertThat(subscription().get("version")).isEqualTo(1L);
            assertThat(processedCount(eventId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("진행 중인 청약이 있으면 같은 회원이 같은 종목에 다시 청약할 수 없고, CANCELLED가 되면 다시 청약할 수 있다")
    void complete_allowsResubscription_afterCancelled() {
        // given
        assertThatThrownBy(() -> insertSubscription(UUID.randomUUID(), userId, "PENDING", null, 1))
                .isInstanceOf(DuplicateKeyException.class);

        // when
        voidedService.complete(event(UUID.randomUUID(), MARGIN_AMOUNT));

        // then
        UUID resubscriptionId = UUID.randomUUID();
        insertSubscription(resubscriptionId, userId, "PENDING", null, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, resubscriptionId))
                .isEqualTo("PENDING");
    }

    private void insertSubscription(UUID id, UUID user, String status, String cancelReason, int attempts) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, ?, ?, now(), now() - interval '1 minute',
                        0, now(), 'test', now(), 'test')""",
                id, UUID.randomUUID(), ipoId, user, UUID.randomUUID(), status, cancelReason, attempts);
    }

    private MarginVoided event(UUID eventId, long amount) {
        return new MarginVoided(eventId, subscriptionId, amount);
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, attempts, version FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, subscriptionId);
    }
}
