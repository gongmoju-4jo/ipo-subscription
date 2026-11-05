package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.AllocationResult;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
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

import java.util.Map;
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
 * 배정 결과({@code allocation.result})와 정산 결과({@code margin.settled})가 같은 {@code CONFIRMED} 청약에 동시에 도착했을 때(결정 9)
 * 어느 쪽이 먼저 커밋하든 같은 최종 값으로 끝나는지를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * <p>
 * 두 토픽은 서로 달라 도착 순서가 보장되지 않음. 낙관적 락({@code version})으로 한쪽만 성공하고, 진 쪽은 Consumer가 재시도해서
 * 이미 바뀐 상태({@code ALLOCATED} 또는 {@code REFUNDED})를 보고 처리해야 함. 이 테스트는 그 재시도를 호출 한 번으로 흉내 냄.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("배정 결과와 정산 결과 처리의 경합 (PostgreSQL)")
class SubscriptionRefundRaceTest {

    private static final int ROUNDS = 20;
    private static final long MARGIN_AMOUNT = 1_500_000;
    private static final long ALLOCATED_QUANTITY = 3;
    private static final long ALLOCATED_AMOUNT = 45_000;
    private static final long REFUND_AMOUNT = MARGIN_AMOUNT - ALLOCATED_AMOUNT;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionAllocateService allocateService;

    @Autowired
    private SubscriptionRefundService refundService;

    // 청약 조회 직후에 끼어들어 한 쪽 트랜잭션을 멈추기 위한 훅 (결정적 경합 테스트용). 조회한 스레드의 훅만 한 번 실행함
    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ThreadLocal<Runnable> afterRead = new ThreadLocal<>();
    private ExecutorService executor;
    private UUID ipoId;
    private UUID accountId;

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
        accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '2 day', now() - interval '1 day', current_date + 5, 'CLOSED', 0,
                        now(), 'test', now(), 'test')""", ipoId);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name IN (?, ?)",
                SubscriptionAllocateService.CONSUMER_NAME, SubscriptionRefundService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    // ---------- 결정적 경합: 진 쪽이 반드시 지는 순서 ----------

    @Test
    @DisplayName("정산 결과가 CONFIRMED를 읽은 뒤 배정 결과가 먼저 커밋되면, 정산은 낙관적 락으로 실패하고 재시도에서 ALLOCATED를 보고 REFUNDED로 끝난다")
    void refundLoses_whenAllocateCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertConfirmed();
        AllocationResult allocation = allocation(subscriptionId);
        MarginSettled settled = settled(subscriptionId);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> refundService.refund(settled),
                () -> allocateService.allocate(allocation));
        RefundResult retried = refundService.refund(settled);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(retried).isEqualTo(RefundResult.REFUNDED);
        assertRefunded(subscriptionId, 0);
        assertThat(processedCount(settled.eventId())).isEqualTo(1);
        assertThat(processedCount(allocation.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("배정 결과가 CONFIRMED를 읽은 뒤 정산 결과가 먼저 커밋되면(결정 9), 배정은 낙관적 락으로 실패하고 재시도에서 같은 값의 REFUNDED를 보고 중복으로 끝난다")
    void allocateLoses_whenRefundCommitsFirst() throws Exception {
        // given
        UUID subscriptionId = insertConfirmed();
        AllocationResult allocation = allocation(subscriptionId);
        MarginSettled settled = settled(subscriptionId);

        // when
        Object loser = loserReadsThenWinnerCommits(
                () -> allocateService.allocate(allocation),
                () -> refundService.refund(settled));
        AllocateResult retried = allocateService.allocate(allocation);

        // then
        assertThat(loser).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(retried).isEqualTo(AllocateResult.ALREADY_ALLOCATED_IGNORED);
        assertRefunded(subscriptionId, 0);
        assertThat(processedCount(settled.eventId())).isEqualTo(1);
        assertThat(processedCount(allocation.eventId())).isEqualTo(1);
    }

    // ---------- 무작위 경합: 어느 쪽이 이기든 같은 최종 값 ----------

    @Test
    @DisplayName("CONFIRMED에 배정 결과와 정산 결과가 동시에 오면 어느 쪽이 먼저 커밋하든, 진 쪽을 재시도한 뒤 같은 배정 결과와 환불액의 REFUNDED로 끝난다")
    void allocateAndRefund_convergeToSameResult_whenConcurrent() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            // given
            UUID subscriptionId = insertConfirmed();
            AllocationResult allocation = allocation(subscriptionId);
            MarginSettled settled = settled(subscriptionId);

            // when
            Outcomes outcomes = race(() -> allocateService.allocate(allocation), () -> refundService.refund(settled));
            retryIfConflict(outcomes.first(), () -> allocateService.allocate(allocation), round);
            retryIfConflict(outcomes.second(), () -> refundService.refund(settled), round);

            // then
            assertRefunded(subscriptionId, round);
            assertThat(processedCount(settled.eventId())).as("round %d: 정산 처리 기록 1건", round).isEqualTo(1);
            assertThat(processedCount(allocation.eventId())).as("round %d: 배정 처리 기록 1건", round).isEqualTo(1);
        }
    }

    // ---------- 검증 ----------

    private void assertRefunded(UUID subscriptionId, int round) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT status, allocated_quantity, allocated_amount, refund_amount
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
        assertThat(row.get("status")).as("round %d: 상태", round).isEqualTo("REFUNDED");
        assertThat(row.get("allocated_quantity")).as("round %d: 배정 수량", round).isEqualTo(ALLOCATED_QUANTITY);
        assertThat(row.get("allocated_amount")).as("round %d: 배정 대금", round).isEqualTo(ALLOCATED_AMOUNT);
        assertThat(row.get("refund_amount")).as("round %d: 환불액", round).isEqualTo(REFUND_AMOUNT);
    }

    // ---------- 경합 실행 ----------

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

    private record Outcomes(Object first, Object second) {
    }

    // 두 작업을 장벽에서 동시에 출발시키고 결과나 예외를 그대로 돌려줌. 무작위 쪽에 0~12ms 지연을 줘서 양쪽 순서가 모두 나오게 함
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
    private void retryIfConflict(Object outcome, Callable<?> retry, int round) throws Exception {
        if (outcome instanceof OptimisticLockingFailureException) {
            retry.call();
        } else if (outcome instanceof Throwable error) {
            throw new AssertionError("round " + round + ": 낙관적 락 충돌이 아닌 예외가 남", error);
        }
    }

    // ---------- 데이터 ----------

    // 마감된 공모주(공모가 15,000원)의 확정 청약 100주(증거금 1,500,000원)
    private UUID insertConfirmed() {
        UUID subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, margin_id, attempts, received_at, confirmed_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, 'CONFIRMED', ?, 0, now() - interval '2 day', now() - interval '2 day',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, MARGIN_AMOUNT, UUID.randomUUID());
        return subscriptionId;
    }

    private AllocationResult allocation(UUID subscriptionId) {
        return new AllocationResult(UUID.randomUUID(), subscriptionId, ipoId, accountId, ALLOCATED_QUANTITY, ALLOCATED_AMOUNT);
    }

    private MarginSettled settled(UUID subscriptionId) {
        return new MarginSettled(UUID.randomUUID(), subscriptionId, ALLOCATED_AMOUNT, REFUND_AMOUNT);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
