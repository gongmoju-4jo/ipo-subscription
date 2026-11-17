package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * 인스턴스가 2개일 때처럼 같은 청약의 시간 초과 처리, 반환 재요청이 두 번 실행돼도 요청 횟수가 한 번만 늘고 보상이 일찍 시작되지 않는지를
 * 실제 PostgreSQL(Testcontainers)로 검증함 (#184). Docker 필요.
 * <p>
 * 스케줄러 락(ShedLock)은 한 번에 한 인스턴스만 실행하게 하지만, 락 최대 시간을 넘긴 실행이나 락이 없는 구성에서도 결과가 같아야 함.
 * 동시에 처리하면 낙관적 락({@code version})으로 한쪽만 커밋되고, 차례로 처리하면 뒤쪽이 마지막 요청 시각을 다시 확인해 건너뜀.
 * 같은 조건의 경합을 {@value #ROUNDS}번 반복해 어느 순서로 끝나든 결과가 같은지 확인하고, 진 쪽이 반드시 지는 순서는 따로 확인함.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("같은 청약의 시간 초과 처리, 반환 재요청이 두 번 실행될 때 (PostgreSQL)")
class SubscriptionSchedulerConcurrentRunTest {

    private static final int ROUNDS = 30;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MARGIN_AMOUNT = 1_500_000;
    private static final VoidRetryBackoff BACKOFF = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(5));

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionReserveTimeoutService timeoutService;

    @Autowired
    private SubscriptionVoidRetryService voidRetryService;

    // 청약 조회 직후에 끼어들어 한 쪽 트랜잭션을 멈추기 위한 훅 (결정적 경합 테스트용). 조회한 스레드의 훅만 한 번 실행함
    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ThreadLocal<Runnable> afterRead = new ThreadLocal<>();
    private ExecutorService executor;
    private UUID ipoId;
    // 재요청 판단 기준 시각. 인출은 이 시각 - 30초보다 이른 요청을 응답 없음으로 보고, 반환은 이 시각에서 대기 시간을 뺀 시각과 비교함
    private Instant now;
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
        now = Instant.now();
        threshold = now.minusSeconds(30);
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
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    // ---------- 인출 재요청 ----------

    @Test
    @DisplayName("최대 횟수 직전(2회)의 PENDING을 동시에 두 번 처리하면 요청 횟수는 한 번만 늘어 3이고 PENDING 그대로이며, 인출 요청 1건만 기록되고 보상은 시작되지 않는다")
    void reserveRetry_incrementsAttemptsOnce_whenHandledConcurrently() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertPending(MAX_ATTEMPTS - 1);

            // when: 두 인스턴스가 같은 기준 시각으로 같은 청약을 처리
            Outcomes raced = race(() -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                    () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));

            // then: 한쪽만 재요청하고, 다른 쪽은 낙관적 락 충돌이거나 이미 다시 요청된 것을 보고 건너뜀
            assertThat(raced.all()).as("round %d: 재요청은 한쪽만", round).filteredOn(ReserveTimeoutResult.RETRIED::equals).hasSize(1);
            Object other = raced.otherThan(ReserveTimeoutResult.RETRIED);
            assertThat(isConflict(other) || other == ReserveTimeoutResult.NOT_TIMED_OUT_IGNORED)
                    .as("round %d: 다른 쪽 결과 %s", round, other).isTrue();
            Map<String, Object> row = subscription(subscriptionId);
            assertThat(row.get("status")).as("round %d: 보상이 일찍 시작되지 않음", round).isEqualTo("PENDING");
            assertThat(row.get("attempts")).as("round %d: 요청 횟수는 한 번만 늘어남", round).isEqualTo(MAX_ATTEMPTS);
            assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).as("round %d: 인출 요청 1건", round).isEqualTo(1);
            assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청 없음", round).isZero();
            outcomes.merge(describe(other), 1, Integer::sum);
        }
        System.out.println("[경합 결과 분포] 인출 재요청 두 번 실행, 진 쪽: " + outcomes);
    }

    @Test
    @DisplayName("한 인스턴스가 PENDING을 읽은 뒤 다른 인스턴스의 재요청이 먼저 커밋되면, 늦은 쪽은 낙관적 락으로 실패해 롤백되고 다음 주기에도 이미 다시 요청된 청약이라 건너뛴다")
    void reserveRetry_loserFailsAndSkipsNextTick_whenOtherInstanceCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS - 1);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
        ReserveTimeoutResult nextTick = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then: 요청 횟수가 최대 횟수에 이르렀어도 마지막 요청 뒤 응답 시간이 지나지 않아 보상하지 않음
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(nextTick).isEqualTo(ReserveTimeoutResult.NOT_TIMED_OUT_IGNORED);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(MAX_ATTEMPTS);
        assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).isEqualTo(1);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isZero();
    }

    // ---------- 시간 초과 보상 ----------

    @Test
    @DisplayName("최대 횟수에 이른 PENDING을 동시에 두 번 처리하면 보상은 한 번만 시작되어 COMPENSATING(TIMEOUT)이고 반환 요청은 1건만 기록된다")
    void timeoutCompensation_startsOnce_whenHandledConcurrently() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertPending(MAX_ATTEMPTS);

            // when
            Outcomes raced = race(() -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                    () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));

            // then: 한쪽만 보상을 시작하고, 다른 쪽은 낙관적 락 충돌이거나 이미 PENDING이 아닌 것을 보고 건너뜀
            assertThat(raced.all()).as("round %d: 보상 시작은 한쪽만", round)
                    .filteredOn(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED::equals).hasSize(1);
            Object other = raced.otherThan(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED);
            assertThat(isConflict(other) || other == ReserveTimeoutResult.NOT_PENDING_IGNORED)
                    .as("round %d: 다른 쪽 결과 %s", round, other).isTrue();
            Map<String, Object> row = subscription(subscriptionId);
            assertThat(row.get("status")).as("round %d: 상태", round).isEqualTo("COMPENSATING");
            assertThat(row.get("cancel_reason")).as("round %d: 해제 사유", round).isEqualTo("TIMEOUT");
            assertThat(row.get("attempts")).as("round %d: 반환 요청 횟수 1", round).isEqualTo(1);
            assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청 1건", round).isEqualTo(1);
            assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).as("round %d: 인출 요청 없음", round).isZero();
            outcomes.merge(describe(other), 1, Integer::sum);
        }
        System.out.println("[경합 결과 분포] 시간 초과 보상 두 번 실행, 진 쪽: " + outcomes);
    }

    @Test
    @DisplayName("한 인스턴스가 최대 횟수에 이른 PENDING을 읽은 뒤 다른 인스턴스의 시간 초과 보상이 먼저 커밋되면, 늦은 쪽은 낙관적 락으로 실패해 롤백되고 다음 주기에는 PENDING이 아니라서 건너뛴다")
    void timeoutCompensation_loserFailsAndSkipsNextTick_whenOtherInstanceCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertPending(MAX_ATTEMPTS);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS),
                () -> timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS));
        ReserveTimeoutResult nextTick = timeoutService.handle(subscriptionId, threshold, MAX_ATTEMPTS);

        // then: 보상은 먼저 커밋한 쪽에서 한 번만 시작되고, 반환 요청도 1건만 기록됨
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(nextTick).isEqualTo(ReserveTimeoutResult.NOT_PENDING_IGNORED);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("cancel_reason")).isEqualTo("TIMEOUT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isEqualTo(1);
        assertThat(outboxCount(subscriptionId, "margin.reserve-requested")).isZero();
    }

    // ---------- 반환 재요청 ----------

    @Test
    @DisplayName("재요청 대기가 끝난 COMPENSATING을 동시에 두 번 처리하면 요청 횟수는 한 번만 늘어 2이고 반환 요청은 1건만 기록된다")
    void voidRetry_incrementsAttemptsOnce_whenHandledConcurrently() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertCompensating(1);

            // when: 두 인스턴스가 같은 기준 시각으로 같은 청약을 처리
            Outcomes raced = race(() -> voidRetryService.handle(subscriptionId, now, BACKOFF).outcome(),
                    () -> voidRetryService.handle(subscriptionId, now, BACKOFF).outcome());

            // then: 한쪽만 재요청하고, 다른 쪽은 낙관적 락 충돌이거나 이미 다시 요청된 것을 보고 건너뜀
            assertThat(raced.all()).as("round %d: 재요청은 한쪽만", round).filteredOn(VoidRetryResult.Outcome.RETRIED::equals).hasSize(1);
            Object other = raced.otherThan(VoidRetryResult.Outcome.RETRIED);
            assertThat(isConflict(other) || other == VoidRetryResult.Outcome.NOT_TIMED_OUT_IGNORED)
                    .as("round %d: 다른 쪽 결과 %s", round, other).isTrue();
            Map<String, Object> row = subscription(subscriptionId);
            assertThat(row.get("status")).as("round %d: 상태 그대로", round).isEqualTo("COMPENSATING");
            assertThat(row.get("attempts")).as("round %d: 요청 횟수는 한 번만 늘어남", round).isEqualTo(2);
            assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청 1건", round).isEqualTo(1);
            outcomes.merge(describe(other), 1, Integer::sum);
        }
        System.out.println("[경합 결과 분포] 반환 재요청 두 번 실행, 진 쪽: " + outcomes);
    }

    @Test
    @DisplayName("한 인스턴스가 COMPENSATING을 읽은 뒤 다른 인스턴스의 반환 재요청이 먼저 커밋되면, 늦은 쪽은 낙관적 락으로 실패해 롤백되고 다음 주기에도 이미 다시 요청된 청약이라 건너뛴다")
    void voidRetry_loserFailsAndSkipsNextTick_whenOtherInstanceCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertCompensating(1);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> voidRetryService.handle(subscriptionId, now, BACKOFF),
                () -> voidRetryService.handle(subscriptionId, now, BACKOFF));
        VoidRetryResult nextTick = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(nextTick.outcome()).isEqualTo(VoidRetryResult.Outcome.NOT_TIMED_OUT_IGNORED);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("attempts")).isEqualTo(2);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isEqualTo(1);
    }

    // ---------- 경합 실행 ----------

    private record Outcomes(Object first, Object second) {

        List<Object> all() {
            return List.of(first, second);
        }

        // 기대한 결과를 낸 쪽이 아닌 다른 쪽의 결과나 예외
        Object otherThan(Object expected) {
            return expected.equals(first) ? second : first;
        }
    }

    // 두 작업을 장벽에서 동시에 출발시키고 결과나 예외를 그대로 돌려줌. 무작위 쪽에 0~12ms 지연을 줘서 동시 커밋과 차례로 커밋하는 순서가 모두 나오게 함
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

    // 스케줄러는 낙관적 락 충돌을 건너뜀
    private boolean isConflict(Object outcome) {
        return outcome instanceof OptimisticLockingFailureException;
    }

    private String describe(Object outcome) {
        return isConflict(outcome) ? "낙관적 락 충돌" : String.valueOf(outcome);
    }

    // ---------- 데이터 ----------

    // 마지막 요청 뒤 40초가 지난 PENDING (응답 시간 30초를 넘김)
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

    // 마지막 요청 뒤 40초가 지난 COMPENSATING(TIMEOUT) (요청 횟수 1의 재요청 대기 30초를 넘김)
    private UUID insertCompensating(int attempts) {
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, 'COMPENSATING', 'TIMEOUT', ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, attempts,
                Timestamp.from(now.minusSeconds(40)));
        return subscriptionId;
    }

    private Map<String, Object> subscription(UUID subscriptionId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, attempts, version
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private long outboxCount(UUID subscriptionId, String topic) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = ?", Long.class, subscriptionId, topic);
    }
}
