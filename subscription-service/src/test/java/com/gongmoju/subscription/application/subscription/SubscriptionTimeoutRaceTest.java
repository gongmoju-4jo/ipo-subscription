package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 시간 초과 보상(스케줄러)과 계좌 응답 처리(Consumer)가 같은 청약에 동시에 도착했을 때 상태와 Outbox가 모순되지 않는지를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * <p>
 * 같은 조건의 경합을 {@value #ROUNDS}번 반복하고, 매번 결과가 허용된 상태 중 하나인지 확인함. 어느 쪽이 먼저 커밋하든
 * 낙관적 락({@code version})으로 한쪽만 성공하고, 진 쪽은 스케줄러가 건너뛰거나(다음 주기에 다시 판단) Consumer가 재시도해서(재시도에서는 이미 바뀐 상태를 보고 무시) 끝나야 함.
 * 이 테스트는 그 재시도, 다음 주기를 호출 한 번으로 흉내 냄.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("시간 초과 보상과 계좌 응답 처리의 경합 (PostgreSQL)")
class SubscriptionTimeoutRaceTest {

    private static final int ROUNDS = 30;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionReserveTimeoutService timeoutService;

    @Autowired
    private SubscriptionConfirmService confirmService;

    @Autowired
    private SubscriptionRejectService rejectService;

    // 청약 조회 직후에 끼어들어 한 쪽 트랜잭션을 멈추기 위한 훅 (결정적 경합 테스트용). 조회한 스레드의 훅만 한 번 실행함
    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ThreadLocal<Runnable> afterRead = new ThreadLocal<>();
    private ExecutorService executor;
    private UUID ipoId;
    // 응답 시간이 지났는지 가르는 기준 시각 (현재 시각 - 30초)
    private Instant threshold;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            Runnable hook = afterRead.get();
            if (hook != null) {
                afterRead.remove();
                hook.run();
            }
            return result;
        }).when(subscriptionRepository).findById(any());
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
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name IN (?, ?)",
                SubscriptionConfirmService.CONSUMER_NAME, SubscriptionRejectService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("마지막 인출 요청에도 응답이 없는 PENDING에서 margin.reserved와 시간 초과 보상이 동시에 오면 CONFIRMED(반환 요청 없음) 또는 COMPENSATING(TIMEOUT, 반환 요청 1건) 중 하나로만 끝난다")
    void confirmVsTimeoutCompensation_endsInConsistentState() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertPending(MAX_ATTEMPTS);
            UUID marginId = UUID.randomUUID();
            MarginReserved reserved = new MarginReserved(UUID.randomUUID(), subscriptionId, marginId, MARGIN_AMOUNT, Instant.now());

            // when
            Object confirmOutcome;
            Object timeoutOutcome;
            Outcomes raced = race(() -> confirmService.confirm(reserved), () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
            confirmOutcome = raced.first();
            timeoutOutcome = raced.second();
            // 진 쪽을 흉내 냄: Consumer는 낙관적 락 충돌이면 재시도하고, 스케줄러는 건너뛰고 다음 주기에 다시 판단함
            retryConsumerIfConflict(confirmOutcome, () -> confirmService.confirm(reserved), round);
            failIfUnexpected(timeoutOutcome, round);

            // then
            String status = assertConsistentOutcome(subscriptionId, marginId, round);
            outcomes.merge(status, 1, Integer::sum);
            assertThat(processedCount(reserved.eventId())).as("round %d: 처리 기록 1건", round).isEqualTo(1);
            assertNoFurtherRecordsOnNextTick(subscriptionId, round);
        }
        System.out.println("[경합 결과 분포] 확정 vs 시간 초과 보상: " + outcomes);
    }

    @Test
    @DisplayName("마지막 인출 요청에도 응답이 없는 PENDING에서 margin.reserve-failed와 시간 초과 보상이 동시에 오면 REJECTED(반환 요청 없음) 또는 COMPENSATING(TIMEOUT, 반환 요청 1건) 중 하나로만 끝난다")
    void rejectVsTimeoutCompensation_endsInConsistentState() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertPending(MAX_ATTEMPTS);
            MarginReserveFailed failed = new MarginReserveFailed(UUID.randomUUID(), subscriptionId, RejectReason.INSUFFICIENT_BALANCE);

            // when
            Outcomes raced = race(() -> rejectService.reject(failed), () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
            retryConsumerIfConflict(raced.first(), () -> rejectService.reject(failed), round);
            failIfUnexpected(raced.second(), round);

            // then
            Map<String, Object> row = subscription(subscriptionId);
            String status = (String) row.get("status");
            outcomes.merge(status, 1, Integer::sum);
            if (status.equals("REJECTED")) {
                assertThat(row.get("reject_reason")).as("round %d: 거절 사유", round).isEqualTo("INSUFFICIENT_BALANCE");
                assertThat(row.get("cancel_reason")).as("round %d: 해제 사유 없음", round).isNull();
                assertThat(row.get("margin_id")).as("round %d: margin_id 없음", round).isNull();
                assertThat(row.get("attempts")).as("round %d: 요청 횟수 0", round).isEqualTo(0);
                assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청 없음", round).isZero();
            } else {
                assertCompensatingByTimeout(row, subscriptionId, round);
                assertThat(row.get("reject_reason")).as("round %d: 거절 사유 없음", round).isNull();
            }
            assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).as("round %d: 인출 재요청 없음", round).isZero();
            assertNoFurtherRecordsOnNextTick(subscriptionId, round);
        }
        System.out.println("[경합 결과 분포] 거절 vs 시간 초과 보상: " + outcomes);
    }

    @Test
    @DisplayName("재요청 대상 PENDING에서 margin.reserved와 재요청이 동시에 오면 항상 CONFIRMED로 끝나고 반환 요청은 생기지 않으며 재요청은 많아야 1건이다")
    void confirmVsRetry_alwaysEndsConfirmedWithoutVoidRequest() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertPending(1);
            UUID marginId = UUID.randomUUID();
            MarginReserved reserved = new MarginReserved(UUID.randomUUID(), subscriptionId, marginId, MARGIN_AMOUNT, Instant.now());

            // when
            Outcomes raced = race(() -> confirmService.confirm(reserved), () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
            retryConsumerIfConflict(raced.first(), () -> confirmService.confirm(reserved), round);
            failIfUnexpected(raced.second(), round);

            // then
            Map<String, Object> row = subscription(subscriptionId);
            assertThat(row.get("status")).as("round %d: 확정은 항상 이김", round).isEqualTo("CONFIRMED");
            assertThat(row.get("margin_id")).as("round %d: margin_id", round).isEqualTo(marginId);
            assertThat(row.get("attempts")).as("round %d: 요청 횟수 0 (상태가 바뀌어서 초기화)", round).isEqualTo(0);
            assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청 없음", round).isZero();
            long retries = outboxCount(subscriptionId, "margin.reserve-requested");
            assertThat(retries).as("round %d: 재요청은 많아야 1건", round).isLessThanOrEqualTo(1);
            outcomes.merge("재요청 " + retries + "건", 1, Integer::sum);
        }
        System.out.println("[경합 결과 분포] 확정 vs 재요청: " + outcomes);
    }

    // ---------- 결정적 경합: 진 쪽이 반드시 지는 순서 ----------

    @Test
    @DisplayName("시간 초과 보상이 청약을 읽은 뒤 margin.reserved가 먼저 커밋되면, 보상은 낙관적 락으로 실패하고 롤백되어 CONFIRMED가 유지되며 반환 요청은 생기지 않는다")
    void timeoutCompensationLoses_whenConfirmCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS);
        UUID marginId = UUID.randomUUID();
        MarginReserved reserved = new MarginReserved(UUID.randomUUID(), subscriptionId, marginId, MARGIN_AMOUNT, Instant.now());

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                () -> confirmService.confirm(reserved));

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(assertConsistentOutcome(subscriptionId, marginId, 0)).isEqualTo("CONFIRMED");
        assertNoFurtherRecordsOnNextTick(subscriptionId, 0);
    }

    @Test
    @DisplayName("margin.reserved가 청약을 읽은 뒤 시간 초과 보상이 먼저 커밋되면, 확정은 낙관적 락으로 실패하고 재시도에서는 COMPENSATING이라 무시되어 COMPENSATING(TIMEOUT)과 반환 요청 1건이 유지된다")
    void confirmLoses_whenTimeoutCompensationCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS);
        UUID marginId = UUID.randomUUID();
        MarginReserved reserved = new MarginReserved(UUID.randomUUID(), subscriptionId, marginId, MARGIN_AMOUNT, Instant.now());

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> confirmService.confirm(reserved),
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
        ConfirmResult retried = confirmService.confirm(reserved);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(retried).isEqualTo(ConfirmResult.UNEXPECTED_STATE_IGNORED);
        assertThat(assertConsistentOutcome(subscriptionId, marginId, 0)).isEqualTo("COMPENSATING");
        assertThat(processedCount(reserved.eventId())).isEqualTo(1);
        assertNoFurtherRecordsOnNextTick(subscriptionId, 0);
    }

    @Test
    @DisplayName("시간 초과 보상이 청약을 읽은 뒤 margin.reserve-failed가 먼저 커밋되면, 보상은 낙관적 락으로 실패하고 롤백되어 REJECTED가 유지되며 반환 요청은 생기지 않는다")
    void timeoutCompensationLoses_whenRejectCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS);
        MarginReserveFailed failed = new MarginReserveFailed(UUID.randomUUID(), subscriptionId, RejectReason.INSUFFICIENT_BALANCE);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                () -> rejectService.reject(failed));

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("reject_reason")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isZero();
        assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).isZero();
        assertNoFurtherRecordsOnNextTick(subscriptionId, 0);
    }

    @Test
    @DisplayName("margin.reserve-failed가 청약을 읽은 뒤 시간 초과 보상이 먼저 커밋되면, 거절은 낙관적 락으로 실패하고 재시도에서는 COMPENSATING이라 무시되어 COMPENSATING(TIMEOUT)과 반환 요청 1건이 유지된다")
    void rejectLoses_whenTimeoutCompensationCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS);
        MarginReserveFailed failed = new MarginReserveFailed(UUID.randomUUID(), subscriptionId, RejectReason.INSUFFICIENT_BALANCE);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> rejectService.reject(failed),
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
        ReserveFailedResult retried = rejectService.reject(failed);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(retried).isEqualTo(ReserveFailedResult.UNEXPECTED_STATE_IGNORED);
        Map<String, Object> row = subscription(subscriptionId);
        assertCompensatingByTimeout(row, subscriptionId, 0);
        assertThat(row.get("reject_reason")).isNull();
        assertNoFurtherRecordsOnNextTick(subscriptionId, 0);
    }

    // 진 쪽 작업이 청약을 읽은 직후 멈추고, 그 사이 이기는 쪽 작업이 메인 스레드에서 커밋한 뒤 진 쪽을 다시 진행시킴. 진 쪽의 결과나 예외를 돌려줌
    private Object loserReadsThenWinnerCommits(Callable<?> loser, Runnable winner) throws Exception {
        CountDownLatch loserHasRead = new CountDownLatch(1);
        CountDownLatch winnerDone = new CountDownLatch(1);
        Future<Object> loserFuture = executor.submit(() -> {
            afterRead.set(() -> {
                loserHasRead.countDown();
                try {
                    winnerDone.await(15, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                return loser.call();
            } catch (Throwable e) {
                return e;
            }
        });
        assertThat(loserHasRead.await(15, TimeUnit.SECONDS)).as("진 쪽이 청약을 읽음").isTrue();
        try {
            winner.run();
        } finally {
            winnerDone.countDown();
        }
        return loserFuture.get(30, TimeUnit.SECONDS);
    }

    // ---------- 검증 ----------

    // CONFIRMED(반환 요청 없음) 또는 COMPENSATING(TIMEOUT, 반환 요청 1건) 중 하나인지 확인하고 상태를 돌려줌
    private String assertConsistentOutcome(UUID subscriptionId, UUID marginId, int round) {
        Map<String, Object> row = subscription(subscriptionId);
        String status = (String) row.get("status");
        if (status.equals("CONFIRMED")) {
            assertThat(row.get("margin_id")).as("round %d: 확정이면 margin_id가 응답의 값", round).isEqualTo(marginId);
            assertThat(row.get("confirmed_at")).as("round %d: 확정 시각", round).isNotNull();
            assertThat(row.get("cancel_reason")).as("round %d: 확정이면 해제 사유 없음", round).isNull();
            assertThat(row.get("attempts")).as("round %d: 요청 횟수 0", round).isEqualTo(0);
            assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 확정이면 반환 요청이 없어야 함", round).isZero();
        } else {
            assertCompensatingByTimeout(row, subscriptionId, round);
            assertThat(row.get("confirmed_at")).as("round %d: 보상이면 확정 시각 없음", round).isNull();
        }
        assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).as("round %d: 한도에 이른 뒤에는 인출 요청을 기록하지 않음", round).isZero();
        return status;
    }

    private void assertCompensatingByTimeout(Map<String, Object> row, UUID subscriptionId, int round) {
        assertThat(row.get("status")).as("round %d: 허용된 상태는 둘뿐", round).isEqualTo("COMPENSATING");
        assertThat(row.get("cancel_reason")).as("round %d: 해제 사유 TIMEOUT", round).isEqualTo("TIMEOUT");
        assertThat(row.get("margin_id")).as("round %d: 보상이면 margin_id 없음", round).isNull();
        assertThat(row.get("attempts")).as("round %d: 반환 요청 횟수 1", round).isEqualTo(1);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 보상이면 반환 요청이 정확히 1건", round).isEqualTo(1);
    }

    // 다음 주기에 스케줄러가 다시 봐도 아무것도 더 기록하지 않아야 함
    private void assertNoFurtherRecordsOnNextTick(UUID subscriptionId, int round) {
        long reserveBefore = outboxCount(subscriptionId, "margin.reserve-requested");
        long voidBefore = outboxCount(subscriptionId, "margin.void-requested");

        ReserveTimeoutResult next = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        assertThat(next).as("round %d: 이미 PENDING이 아니라 건너뜀", round).isEqualTo(ReserveTimeoutResult.NOT_PENDING_IGNORED);
        assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).as("round %d: 인출 요청이 늘지 않음", round).isEqualTo(reserveBefore);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청이 늘지 않음", round).isEqualTo(voidBefore);
    }

    // ---------- 경합 실행 ----------

    private record Outcomes(Object first, Object second) {
    }

    // 두 작업을 장벽에서 동시에 출발시키고 결과나 예외를 그대로 돌려줌.
    // 시간 초과 보상 쪽이 하는 일이 더 많아서 그냥 출발시키면 응답 처리가 항상 먼저 끝나므로, 무작위 쪽에 0~12ms 지연을 줘서 양쪽 순서가 모두 나오게 함
    private Outcomes race(Callable<?> first, Callable<?> second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        boolean delayFirst = ThreadLocalRandom.current().nextBoolean();
        long delayMillis = ThreadLocalRandom.current().nextLong(0, 13);
        Future<Object> a = executor.submit(() -> run(barrier, first, delayFirst ? delayMillis : 0));
        Future<Object> b = executor.submit(() -> run(barrier, second, delayFirst ? 0 : delayMillis));
        return new Outcomes(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
    }

    private Object run(CyclicBarrier barrier, Callable<?> task, long delayMillis) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        try {
            return task.call();
        } catch (Throwable e) {
            return e;
        }
    }

    // Consumer는 낙관적 락 충돌이면 재시도함. 그 외 예외는 허용하지 않음
    private void retryConsumerIfConflict(Object outcome, Callable<?> retry, int round) throws Exception {
        if (outcome instanceof OptimisticLockingFailureException) {
            retry.call();
        } else {
            failIfUnexpected(outcome, round);
        }
    }

    // 스케줄러는 낙관적 락 충돌을 건너뜀. 그 외 예외는 허용하지 않음
    private void failIfUnexpected(Object outcome, int round) {
        if (outcome instanceof Throwable error && !(error instanceof OptimisticLockingFailureException)) {
            throw new AssertionError("round " + round + ": 낙관적 락 충돌이 아닌 예외가 남", error);
        }
    }

    // ---------- 데이터 ----------

    private UUID insertPending(int attempts) {
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, 'PENDING', ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, attempts,
                Timestamp.from(threshold.minusSeconds(10)));
        return subscriptionId;
    }

    private Map<String, Object> subscription(UUID subscriptionId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, reject_reason, cancel_reason, margin_id, confirmed_at, attempts, version
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private long outboxCount(UUID subscriptionId, String topic) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = ?", Long.class, subscriptionId, topic);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
