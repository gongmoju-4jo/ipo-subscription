package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.application.subscription.VoidRetryResult.Outcome;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
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
 * 반환 재요청(스케줄러)과 계좌의 반환 응답 처리({@code margin.voided} Consumer)가 같은 청약에 동시에 도착했을 때 상태와 Outbox가 어긋나지 않는지를
 * 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * <p>
 * 어느 쪽이 먼저 커밋하든 낙관적 락({@code version})으로 한쪽만 성공하고, 진 쪽은 스케줄러가 건너뛰거나(다음 주기에 다시 판단) Consumer가
 * 재시도함. 이 테스트는 그 재시도, 다음 주기를 호출 한 번으로 흉내 냄. 최종 상태는 항상 {@code CANCELLED}이고, 재요청이 먼저 커밋된 경우에만
 * 반환 요청 Outbox가 1건 남음 (계좌는 이미 반환했으면 같은 응답을 다시 보낼 뿐이라 해가 없고, 청약은 중복 응답으로 정상 종료함).
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("반환 재요청과 반환 응답 처리의 경합 (PostgreSQL)")
class SubscriptionVoidRetryRaceTest {

    private static final int ROUNDS = 30;
    // 기본 설정과 같은 값 (요청 횟수 1일 때 대기 시간 30초)
    private static final VoidRetryBackoff BACKOFF = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(5));
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionVoidRetryService voidRetryService;

    @Autowired
    private SubscriptionVoidedService voidedService;

    // 청약 조회 직후에 끼어들어 한 쪽 트랜잭션을 멈추기 위한 훅 (결정적 경합 테스트용). 조회한 스레드의 훅만 한 번 실행함
    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ThreadLocal<Runnable> afterRead = new ThreadLocal<>();
    private ExecutorService executor;
    private UUID ipoId;
    // 재요청 판단 기준 시각
    private Instant now;

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
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionVoidedService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("COMPENSATING에서 반환 응답과 반환 재요청이 동시에 오면 어느 쪽이 먼저든 최종 CANCELLED(요청 횟수 0)이고 반환 요청 Outbox는 0건 또는 1건이다")
    void voidedVsRetry_endsCancelled() throws Exception {
        // given
        Map<String, Integer> outcomes = new TreeMap<>();

        for (int round = 1; round <= ROUNDS; round++) {
            UUID subscriptionId = insertCompensating(1);
            MarginVoided voided = new MarginVoided(UUID.randomUUID(), subscriptionId, 0);

            // when
            Outcomes raced = race(() -> voidedService.complete(voided), () -> voidRetryService.handle(subscriptionId, now, BACKOFF));
            // 진 쪽을 흉내 냄: Consumer는 낙관적 락 충돌이면 재시도하고, 스케줄러는 건너뛰고 다음 주기에 다시 판단함
            retryConsumerIfConflict(raced.first(), () -> voidedService.complete(voided), round);
            failIfUnexpected(raced.second(), round);

            // then
            Map<String, Object> row = subscription(subscriptionId);
            assertThat(row.get("status")).as("round %d: 최종 상태", round).isEqualTo("CANCELLED");
            assertThat(row.get("cancel_reason")).as("round %d: 해제 사유 유지", round).isEqualTo("TIMEOUT");
            assertThat(row.get("attempts")).as("round %d: 취소가 끝나면 요청 횟수 0", round).isEqualTo(0);
            long voidRequests = outboxCount(subscriptionId, "margin.void-requested");
            assertThat(voidRequests).as("round %d: 반환 요청은 재요청이 먼저 커밋된 경우에만 1건", round).isBetween(0L, 1L);
            assertThat(processedCount(voided.eventId())).as("round %d: 처리 기록 1건", round).isEqualTo(1);
            assertNoFurtherRecordsOnNextTick(subscriptionId, voidRequests, round);
            outcomes.merge(voidRequests == 0 ? "재요청이 지거나 건너뜀" : "재요청이 먼저 커밋", 1, Integer::sum);
        }

        // 경합이 한쪽으로만 끝나서 검증이 의미 없어지지 않았는지 확인할 수 있게 분포를 남김
        System.out.println("[반환 응답 vs 반환 재요청] 결과 분포: " + outcomes);
    }

    @Test
    @DisplayName("재요청이 청약을 읽은 뒤 반환 응답이 먼저 커밋되면, 재요청은 낙관적 락으로 실패하고 다음 주기에는 CANCELLED라 건너뛰며 반환 요청은 기록되지 않는다")
    void retryLoses_whenVoidedCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertCompensating(1);
        MarginVoided voided = new MarginVoided(UUID.randomUUID(), subscriptionId, 0);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> voidRetryService.handle(subscriptionId, now, BACKOFF),
                () -> voidedService.complete(voided));
        VoidRetryResult nextTick = voidRetryService.handle(subscriptionId, now, BACKOFF);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(nextTick.outcome()).isEqualTo(Outcome.NOT_VOIDING_IGNORED);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isZero();
    }

    @Test
    @DisplayName("반환 응답이 청약을 읽은 뒤 재요청이 먼저 커밋되면, 응답 처리는 낙관적 락으로 실패하고 재시도에서 CANCELLED가 되며 재요청 Outbox 1건이 남는다")
    void voidedLoses_whenRetryCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertCompensating(1);
        MarginVoided voided = new MarginVoided(UUID.randomUUID(), subscriptionId, 0);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> voidedService.complete(voided),
                () -> voidRetryService.handle(subscriptionId, now, BACKOFF));
        VoidedResult retried = voidedService.complete(voided);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(retried).isEqualTo(VoidedResult.CANCELLED);
        Map<String, Object> row = subscription(subscriptionId);
        assertThat(row.get("status")).isEqualTo("CANCELLED");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).isEqualTo(1);
        assertThat(processedCount(voided.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("재요청이 먼저 커밋돼 계좌가 같은 응답을 다시 보내도 청약은 이미 CANCELLED라 중복 응답으로 정상 종료하고 상태가 바뀌지 않는다")
    void duplicateVoidedAfterRetry_isIgnored() {
        // given
        UUID subscriptionId = insertCompensating(1);
        voidRetryService.handle(subscriptionId, now, BACKOFF);
        voidedService.complete(new MarginVoided(UUID.randomUUID(), subscriptionId, 0));

        // when
        VoidedResult duplicate = voidedService.complete(new MarginVoided(UUID.randomUUID(), subscriptionId, 0));

        // then
        assertThat(duplicate).isEqualTo(VoidedResult.ALREADY_CANCELLED_IGNORED);
        assertThat(subscription(subscriptionId).get("status")).isEqualTo("CANCELLED");
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

    // 다음 주기에 스케줄러가 다시 봐도 아무것도 더 기록하지 않아야 함
    private void assertNoFurtherRecordsOnNextTick(UUID subscriptionId, long voidRequestsBefore, int round) {
        VoidRetryResult next = voidRetryService.handle(subscriptionId, now, BACKOFF);

        assertThat(next.outcome()).as("round %d: 이미 CANCELLED라 건너뜀", round).isEqualTo(Outcome.NOT_VOIDING_IGNORED);
        assertThat(outboxCount(subscriptionId, "margin.void-requested")).as("round %d: 반환 요청이 늘지 않음", round).isEqualTo(voidRequestsBefore);
    }

    // ---------- 경합 실행 ----------

    private record Outcomes(Object first, Object second) {
    }

    // 두 작업을 장벽에서 동시에 출발시키고 결과나 예외를 그대로 돌려줌. 무작위 쪽에 0~12ms 지연을 줘서 양쪽이 먼저 끝나는 경우가 모두 나오게 함
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

    private UUID insertCompensating(int attempts) {
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, 'COMPENSATING', 'TIMEOUT', ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, attempts,
                // 요청 횟수 1의 대기 시간(30초)보다 10초 더 지남
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

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
