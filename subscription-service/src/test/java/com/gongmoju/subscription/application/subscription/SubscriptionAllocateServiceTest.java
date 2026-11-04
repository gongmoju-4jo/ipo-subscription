package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.AllocationResult;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배정 결과 처리(처리 기록, 청약 배정)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 배정 처리 (PostgreSQL)")
class SubscriptionAllocateServiceTest {

    private static final long OFFER_PRICE = 15_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionAllocateService allocateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID accountId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 접수 Consumer만 사용) SQL로 직접 저장함. 마감된 공모주의 확정({@code CONFIRMED}) 청약 100주로 시작함.
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
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionAllocateService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "배정 수량 {0}")
    @ValueSource(longs = {0, 3, 100})
    @DisplayName("CONFIRMED 청약은 ALLOCATED가 되고 배정 수량, 배정 대금이 저장되며 처리 기록이 남는다 (0주, 청약 수량 전부 포함)")
    void allocate_changesToAllocated(long allocatedQuantity) {
        // given
        AllocationResult event = event(UUID.randomUUID(), allocatedQuantity, allocatedQuantity * OFFER_PRICE);

        // when
        AllocateResult result = allocateService.allocate(event);

        // then
        assertThat(result).isEqualTo(AllocateResult.ALLOCATED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("ALLOCATED");
        assertThat(row.get("allocated_quantity")).isEqualTo(allocatedQuantity);
        assertThat(row.get("allocated_amount")).isEqualTo(allocatedQuantity * OFFER_PRICE);
        assertThat(row.get("refund_amount")).isNull();
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 상태를 바꾸지 않고 DUPLICATE_EVENT_IGNORED로 끝나며 처리 기록은 1개다")
    void allocate_ignores_whenSameEventIdArrivesAgain() {
        // given
        AllocationResult event = event(UUID.randomUUID(), 3, 45_000);
        allocateService.allocate(event);

        // when
        AllocateResult result = allocateService.allocate(event);

        // then
        assertThat(result).isEqualTo(AllocateResult.DUPLICATE_EVENT_IGNORED);
        assertThat(subscription().get("version")).isEqualTo(1L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ALLOCATED", "REFUNDED"})
    @DisplayName("이미 배정 결과를 저장한 청약에 eventId가 다른 같은 결과가 오면 ALREADY_ALLOCATED_IGNORED로 끝나고 상태와 값이 유지된다")
    void allocate_ignores_whenSameAllocationArrivesWithNewEventId(String status) {
        // given
        storeAllocation(status, 3, 45_000);
        AllocationResult event = event(UUID.randomUUID(), 3, 45_000);

        // when
        AllocateResult result = allocateService.allocate(event);

        // then
        assertThat(result).isEqualTo(AllocateResult.ALREADY_ALLOCATED_IGNORED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}, 배정 수량 {1}, 배정 대금 {2}")
    @CsvSource({
            "ALLOCATED, 4, 60000",
            "ALLOCATED, 3, 45001",
            "REFUNDED, 2, 30000"
    })
    @DisplayName("이미 저장한 배정 결과와 다른 결과가 오면 덮어쓰지 않고 InvalidEventException이 나며 처리 기록도 남지 않는다 (재시도 없이 DLT 대상)")
    void allocate_throwsAndKeepsValues_whenAllocationDiffers(String status, long allocatedQuantity, long allocatedAmount) {
        // given
        storeAllocation(status, 3, 45_000);
        AllocationResult event = event(UUID.randomUUID(), allocatedQuantity, allocatedAmount);

        // when, then
        assertThatThrownBy(() -> allocateService.allocate(event)).isInstanceOf(InvalidEventException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("allocated_quantity")).isEqualTo(3L);
        assertThat(row.get("allocated_amount")).isEqualTo(45_000L);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @ParameterizedTest(name = "배정 수량 {0}, 배정 대금 {1}")
    @CsvSource({
            "101, 1515000",
            "3, 44999",
            "3, 45001",
            "0, 15000"
    })
    @DisplayName("배정 수량이 청약 수량보다 크거나 배정 대금이 배정 수량 × 공모가와 다르면 InvalidEventException이 나고 CONFIRMED로 남으며 처리 기록도 남지 않는다")
    void allocate_throwsAndKeepsState_whenValuesAreInvalid(long allocatedQuantity, long allocatedAmount) {
        // given
        AllocationResult event = event(UUID.randomUUID(), allocatedQuantity, allocatedAmount);

        // when, then
        assertThatThrownBy(() -> allocateService.allocate(event)).isInstanceOf(InvalidEventException.class);
        assertConfirmedWithoutRecord(event.eventId());
    }

    @Test
    @DisplayName("ipoId가 청약의 공모주와 다르면 InvalidEventException이 나고 CONFIRMED로 남는다")
    void allocate_throws_whenIpoIdDiffers() {
        // given
        AllocationResult event = new AllocationResult(UUID.randomUUID(), subscriptionId, UUID.randomUUID(), accountId, 3, 45_000);

        // when, then
        assertThatThrownBy(() -> allocateService.allocate(event)).isInstanceOf(InvalidEventException.class);
        assertConfirmedWithoutRecord(event.eventId());
    }

    @Test
    @DisplayName("accountId가 청약의 계좌와 다르면 InvalidEventException이 나고 CONFIRMED로 남는다")
    void allocate_throws_whenAccountIdDiffers() {
        // given
        AllocationResult event = new AllocationResult(UUID.randomUUID(), subscriptionId, ipoId, UUID.randomUUID(), 3, 45_000);

        // when, then
        assertThatThrownBy(() -> allocateService.allocate(event)).isInstanceOf(InvalidEventException.class);
        assertConfirmedWithoutRecord(event.eventId());
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId면 InvalidEventException이 나고 처리 기록이 남지 않는다")
    void allocate_throws_whenSubscriptionNotFound() {
        // given
        AllocationResult event = new AllocationResult(UUID.randomUUID(), UUID.randomUUID(), ipoId, accountId, 3, 45_000);

        // when, then
        assertThatThrownBy(() -> allocateService.allocate(event)).isInstanceOf(InvalidEventException.class);
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
    @DisplayName("CONFIRMED, ALLOCATED, REFUNDED 외 상태면 UNEXPECTED_STATE_IGNORED로 끝나고 상태는 바뀌지 않으며 처리 기록은 남는다")
    void allocate_ignores_whenStatusIsUnexpected(String status, String cancelReason) {
        // given
        String rejectReason = "REJECTED".equals(status) ? "INSUFFICIENT_BALANCE" : null;
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = ?, reject_reason = ? WHERE subscription_id = ?",
                status, cancelReason, rejectReason, subscriptionId);
        AllocationResult event = event(UUID.randomUUID(), 3, 45_000);

        // when
        AllocateResult result = allocateService.allocate(event);

        // then
        assertThat(result).isEqualTo(AllocateResult.UNEXPECTED_STATE_IGNORED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("allocated_quantity")).isNull();
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    private void assertConfirmedWithoutRecord(UUID eventId) {
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("allocated_quantity")).isNull();
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(processedCount(eventId)).isZero();
    }

    // margin.settled가 먼저 와서 REFUNDED가 된 경우(결정 9)도 같은 값으로 저장되어 있음
    private void storeAllocation(String status, long allocatedQuantity, long allocatedAmount) {
        Long refundAmount = "REFUNDED".equals(status) ? 1_500_000 - allocatedAmount : null;
        jdbcTemplate.update("""
                UPDATE subscription SET status = ?, allocated_quantity = ?, allocated_amount = ?, refund_amount = ?
                WHERE subscription_id = ?""", status, allocatedQuantity, allocatedAmount, refundAmount, subscriptionId);
    }

    private AllocationResult event(UUID eventId, long allocatedQuantity, long allocatedAmount) {
        return new AllocationResult(eventId, subscriptionId, ipoId, accountId, allocatedQuantity, allocatedAmount);
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
