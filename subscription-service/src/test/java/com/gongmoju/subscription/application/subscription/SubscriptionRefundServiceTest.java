package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.AllocationResult;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
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
 * 정산 결과 처리(처리 기록, 청약 환불 완료)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 환불 완료 처리 (PostgreSQL)")
class SubscriptionRefundServiceTest {

    private static final long OFFER_PRICE = 15_000;
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRefundService refundService;

    @Autowired
    private SubscriptionAllocateService allocateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID accountId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함.
     * 마감된 공모주(공모가 15,000원)의 확정({@code CONFIRMED}) 청약 100주(증거금 1,500,000원)로 시작함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '2 day', now() - interval '1 day', current_date + 5, 'CLOSED', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, margin_id, attempts, received_at, confirmed_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, 'CONFIRMED', ?, 0, now() - interval '2 day', now() - interval '2 day',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name IN (?, ?)",
                SubscriptionRefundService.CONSUMER_NAME, SubscriptionAllocateService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "배정 수량 {0}")
    @ValueSource(longs = {0, 3, 100})
    @DisplayName("ALLOCATED 청약은 REFUNDED가 되고 환불액이 저장되며 처리 기록이 남는다 (0주, 청약 수량 전부 포함)")
    void refund_changesToRefunded_whenAllocated(long allocatedQuantity) {
        // given
        long allocatedAmount = allocatedQuantity * OFFER_PRICE;
        storeAllocation("ALLOCATED", allocatedQuantity, allocatedAmount, null);
        MarginSettled event = event(UUID.randomUUID(), allocatedAmount, MARGIN_AMOUNT - allocatedAmount);

        // when
        RefundResult result = refundService.refund(event);

        // then
        assertThat(result).isEqualTo(RefundResult.REFUNDED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("allocated_quantity")).isEqualTo(allocatedQuantity);
        assertThat(row.get("refund_amount")).isEqualTo(MARGIN_AMOUNT - allocatedAmount);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @ParameterizedTest(name = "정산 금액 {0}")
    @ValueSource(longs = {0, 45_000, 1_500_000})
    @DisplayName("CONFIRMED 청약에 먼저 온 정산 결과는 배정 수량을 정산 금액 ÷ 공모가로 계산해 ALLOCATED를 거쳐 REFUNDED로 바꾼다 (결정 9)")
    void refund_allocatesAndRefunds_whenConfirmed(long postedAmount) {
        // given
        MarginSettled event = event(UUID.randomUUID(), postedAmount, MARGIN_AMOUNT - postedAmount);

        // when
        RefundResult result = refundService.refund(event);

        // then
        assertThat(result).isEqualTo(RefundResult.ALLOCATED_AND_REFUNDED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("allocated_quantity")).isEqualTo(postedAmount / OFFER_PRICE);
        assertThat(row.get("allocated_amount")).isEqualTo(postedAmount);
        assertThat(row.get("refund_amount")).isEqualTo(MARGIN_AMOUNT - postedAmount);
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("정산 결과가 먼저 와서 REFUNDED가 된 뒤 같은 값의 배정 결과가 오면 값을 바꾸지 않고 중복으로 끝난다 (결정 9)")
    void allocationResultArrivingLater_isIgnored_afterRefund() {
        // given
        refundService.refund(event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000));
        AllocationResult allocationResult = new AllocationResult(UUID.randomUUID(), subscriptionId, ipoId, accountId, 3, 45_000);

        // when
        AllocateResult result = allocateService.allocate(allocationResult);

        // then
        assertThat(result).isEqualTo(AllocateResult.ALREADY_ALLOCATED_IGNORED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("version")).isEqualTo(1L);
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 상태를 바꾸지 않고 DUPLICATE_EVENT_IGNORED로 끝나며 처리 기록은 1개다")
    void refund_ignores_whenSameEventIdArrivesAgain() {
        // given
        MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);
        refundService.refund(event);

        // when
        RefundResult result = refundService.refund(event);

        // then
        assertThat(result).isEqualTo(RefundResult.DUPLICATE_EVENT_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 REFUNDED인 청약에 eventId가 다른 같은 정산 결과가 오면 ALREADY_REFUNDED_IGNORED로 끝나고 값이 유지된다")
    void refund_ignores_whenSameRefundArrivesWithNewEventId() {
        // given
        storeAllocation("REFUNDED", 3, 45_000, MARGIN_AMOUNT - 45_000);
        MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);

        // when
        RefundResult result = refundService.refund(event);

        // then
        assertThat(result).isEqualTo(RefundResult.ALREADY_REFUNDED_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @ParameterizedTest(name = "정산 금액 {0}, 환불액 {1}")
    @CsvSource({
            "60000, 1440000",
            "45000, 1455001",
            "45000, 1454999"
    })
    @DisplayName("이미 REFUNDED인 청약에 다른 정산 결과가 오면 덮어쓰지 않고 InvalidEventException이 나며 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void refund_throwsAndKeepsValues_whenRefundDiffers(long postedAmount, long refundAmount) {
        // given
        storeAllocation("REFUNDED", 3, 45_000, MARGIN_AMOUNT - 45_000);
        MarginSettled event = event(UUID.randomUUID(), postedAmount, refundAmount);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat(row.get("allocated_amount")).isEqualTo(45_000L);
        assertThat(row.get("refund_amount")).isEqualTo(MARGIN_AMOUNT - 45_000);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @ParameterizedTest(name = "정산 금액 {0}, 환불액 {1}")
    @CsvSource({
            "60000, 1440000",
            "30000, 1470000",
            "45000, 1455001",
            "45000, 1454999"
    })
    @DisplayName("ALLOCATED 청약에 정산 금액이 배정 대금과 다르거나 환불액이 증거금 − 배정 대금과 다른 결과가 오면 InvalidEventException이 나고 ALLOCATED로 남는다")
    void refund_throwsAndKeepsState_whenAllocatedAmountsDiffer(long postedAmount, long refundAmount) {
        // given
        storeAllocation("ALLOCATED", 3, 45_000, null);
        MarginSettled event = event(UUID.randomUUID(), postedAmount, refundAmount);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("refund_amount")).isNull();
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @ParameterizedTest(name = "정산 금액 {0}, 환불액 {1}")
    @CsvSource({
            "45001, 1454999",
            "1515000, 0",
            "45000, 1455001",
            "45000, 1454999"
    })
    @DisplayName("CONFIRMED 청약에 먼저 온 정산 금액이 공모가로 나누어떨어지지 않거나, 청약 수량보다 많거나, 환불액이 맞지 않으면 InvalidEventException이 나고 CONFIRMED로 남는다")
    void refund_throwsAndKeepsState_whenConfirmedAmountsAreInvalid(long postedAmount, long refundAmount) {
        // given
        MarginSettled event = event(UUID.randomUUID(), postedAmount, refundAmount);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        assertConfirmedWithoutRecord(event.eventId());
    }

    @Test
    @DisplayName("배정 대금이 없는 ALLOCATED 청약이면 IllegalStateException이 나고 ALLOCATED로 남으며 처리 기록도 남지 않는다 (재시도 대상)")
    void refund_throwsAndRollsBackProcessedEvent_whenAllocatedAmountIsMissing() {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = 'ALLOCATED' WHERE subscription_id = ?", subscriptionId);
        MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(IllegalStateException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("refund_amount")).isNull();
        assertThat(processedCount(event.eventId())).isZero();
    }

    @Test
    @DisplayName("같은 청약에 서로 다른 eventId의 정산 결과가 동시에 오면 한 번만 환불 완료되고 나머지는 무시되거나 재시도 대상 예외로 끝난다")
    void refund_refundsOnce_whenConcurrent() throws Exception {
        // given
        storeAllocation("ALLOCATED", 3, 45_000, null);
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<RefundResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return refundService.refund(event);
                }));
            }
            int refunded = 0;
            for (Future<RefundResult> future : futures) {
                try {
                    RefundResult result = future.get(30, TimeUnit.SECONDS);
                    if (result == RefundResult.REFUNDED) {
                        refunded++;
                    } else {
                        assertThat(result).isEqualTo(RefundResult.ALREADY_REFUNDED_IGNORED);
                    }
                } catch (ExecutionException e) {
                    // 낙관적 락 충돌은 Consumer의 에러 핸들러가 재시도하는 예외라 허용함 (재시도하면 REFUNDED 이후로 무시됨)
                    assertThat(e.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }

            // then
            assertThat(refunded).isEqualTo(1);
            Map<String, Object> row = subscription();
            assertThat(row.get("status")).isEqualTo("REFUNDED");
            assertThat(row.get("refund_amount")).isEqualTo(MARGIN_AMOUNT - 45_000);
            assertThat(row.get("version")).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 eventId의 정산 결과를 동시에 처리해도 청약 전환과 처리 기록은 한 번만 남는다")
    void refund_refundsOnce_whenSameEventIsConcurrent() throws Exception {
        // given
        storeAllocation("ALLOCATED", 3, 45_000, null);
        UUID eventId = UUID.randomUUID();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<RefundResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return refundService.refund(event(eventId, 45_000, MARGIN_AMOUNT - 45_000));
                }));
            }
            List<RefundResult> results = new ArrayList<>();
            for (Future<RefundResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            // then
            assertThat(results).containsExactlyInAnyOrder(
                    RefundResult.REFUNDED,
                    RefundResult.DUPLICATE_EVENT_IGNORED,
                    RefundResult.DUPLICATE_EVENT_IGNORED,
                    RefundResult.DUPLICATE_EVENT_IGNORED);
            assertThat(subscription().get("status")).isEqualTo("REFUNDED");
            assertThat(subscription().get("version")).isEqualTo(1L);
            assertThat(processedCount(eventId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 InvalidEventException이 나고 처리 기록이 남지 않는다")
    void refund_throws_whenSubscriptionNotFound() {
        // given
        MarginSettled event = new MarginSettled(UUID.randomUUID(), UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "PENDING, NULL",
            "REJECTED, NULL",
            "COMPENSATING, CONFIRM_FAILED",
            "CANCEL_REQUESTED, USER_CANCEL",
            "CANCELLED, TIMEOUT"
    }, nullValues = "NULL")
    @DisplayName("CONFIRMED, ALLOCATED, REFUNDED 외 상태면 InvalidEventException이 나고 상태는 바뀌지 않으며 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void refund_throwsAndKeepsState_whenStatusIsUnexpected(String status, String cancelReason) {
        // given
        changeStatus(status, cancelReason);
        MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);

        // when, then
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("allocated_quantity")).isNull();
        assertThat(row.get("refund_amount")).isNull();
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "PENDING, NULL",
            "REJECTED, NULL",
            "COMPENSATING, CONFIRM_FAILED",
            "CANCEL_REQUESTED, USER_CANCEL",
            "CANCELLED, TIMEOUT"
    }, nullValues = "NULL")
    @DisplayName("상태 문제로 DLT에 갔던 메시지를 같은 eventId로 다시 처리해도 처리 기록이 남지 않았으므로 청약을 고친 뒤에는 REFUNDED가 된다")
    void refund_refunds_whenSameEventIsRedeliveredAfterStateIsFixed(String status, String cancelReason) {
        // given
        changeStatus(status, cancelReason);
        MarginSettled event = event(UUID.randomUUID(), 45_000, MARGIN_AMOUNT - 45_000);
        assertThatThrownBy(() -> refundService.refund(event)).isInstanceOf(InvalidEventException.class);
        changeStatus("CONFIRMED", null);

        // when
        RefundResult result = refundService.refund(event);

        // then
        assertThat(result).isEqualTo(RefundResult.ALLOCATED_AND_REFUNDED);
        assertThat(subscription().get("status")).isEqualTo("REFUNDED");
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    private void changeStatus(String status, String cancelReason) {
        String rejectReason = "REJECTED".equals(status) ? "INSUFFICIENT_BALANCE" : null;
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = ?, reject_reason = ? WHERE subscription_id = ?",
                status, cancelReason, rejectReason, subscriptionId);
    }

    private void assertConfirmedWithoutRecord(UUID eventId) {
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("allocated_quantity")).isNull();
        assertThat(row.get("refund_amount")).isNull();
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(eventId)).isZero();
    }

    private void storeAllocation(String status, long allocatedQuantity, long allocatedAmount, Long refundAmount) {
        jdbcTemplate.update("""
                UPDATE subscription SET status = ?, allocated_quantity = ?, allocated_amount = ?, refund_amount = ?
                WHERE subscription_id = ?""", status, allocatedQuantity, allocatedAmount, refundAmount, subscriptionId);
    }

    private MarginSettled event(UUID eventId, long postedAmount, long refundAmount) {
        return new MarginSettled(eventId, subscriptionId, postedAmount, refundAmount);
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, allocated_quantity, allocated_amount, refund_amount, version
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
