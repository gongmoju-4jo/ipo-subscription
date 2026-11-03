package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.ipo.AllocationFinished;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 배정 완료 처리(처리 기록, 공모주 CLOSED → ALLOCATED, 배정 합계 확인)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("공모주 배정 완료 처리 (PostgreSQL)")
class IpoAllocationFinishServiceTest {

    private static final long RETAIL_QUANTITY = 100000;
    private static final String QUANTITY_ALERT = "배정 전체 수량이 일반청약 물량보다 많음";
    private static final String COUNT_ALERT = "배정 청약 건수가 확정 청약 수와 다름";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private IpoAllocationFinishService allocationFinishService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 접수 Consumer만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, ?, 50000, 10, 1000, 1.00,
                        now() - interval '2 day', now() - interval '1 day', current_date + 5, 'CLOSED', 0,
                        now(), 'test', now(), 'test')""", ipoId, RETAIL_QUANTITY);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", IpoAllocationFinishService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("CLOSED 공모주는 ALLOCATED가 되고 처리 기록이 남으며, 합계가 맞으면 알림 로그가 없다")
    void finish_changesClosedToAllocated(CapturedOutput output) {
        // given
        insertSubscription("CONFIRMED");
        insertSubscription("ALLOCATED");
        insertSubscription("REFUNDED");
        insertSubscription("REJECTED");
        insertSubscription("CANCELLED");
        AllocationFinished event = event(UUID.randomUUID(), RETAIL_QUANTITY, 3);

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event);

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.ALLOCATED);
        assertThat(ipoStatus()).isEqualTo("ALLOCATED");
        assertThat(processedCount(event.eventId())).isEqualTo(1);
        assertThat(output.getOut()).doesNotContain(QUANTITY_ALERT).doesNotContain(COUNT_ALERT);
    }

    @Test
    @DisplayName("같은 eventId를 다시 받으면 DUPLICATE_EVENT_IGNORED로 끝나고 처리 기록은 1개다")
    void finish_ignores_whenSameEventIdArrivesAgain() {
        // given
        AllocationFinished event = event(UUID.randomUUID(), 0, 0);
        allocationFinishService.finish(event);

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event);

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.DUPLICATE_EVENT_IGNORED);
        assertThat(processedCount(event.eventId())).isEqualTo(1);
    }

    @Test
    @DisplayName("eventId가 달라도 이미 ALLOCATED인 공모주면 ALREADY_ALLOCATED_IGNORED로 끝난다")
    void finish_ignores_whenAlreadyAllocatedWithNewEventId() {
        // given
        allocationFinishService.finish(event(UUID.randomUUID(), 0, 0));

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event(UUID.randomUUID(), 0, 0));

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.ALREADY_ALLOCATED_IGNORED);
        assertThat(ipoStatus()).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("OPEN 공모주면 상태를 바꾸지 않고 UNEXPECTED_STATE_IGNORED로 끝나며 에러 로그를 남긴다")
    void finish_ignores_whenOpen(CapturedOutput output) {
        // given
        jdbcTemplate.update("UPDATE ipo SET status = 'OPEN' WHERE ipo_id = ?", ipoId);

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event(UUID.randomUUID(), 0, 0));

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.UNEXPECTED_STATE_IGNORED);
        assertThat(ipoStatus()).isEqualTo("OPEN");
        assertThat(output.getOut()).contains("정상적으로는 오지 않는 배정 완료 이벤트");
    }

    @Test
    @DisplayName("없는 ipoId면 InvalidEventException이고 처리 기록도 롤백된다")
    void finish_throws_whenIpoNotFound() {
        // given
        AllocationFinished event = new AllocationFinished(UUID.randomUUID(), UUID.randomUUID(), 0, 0);

        // when, then
        assertThatThrownBy(() -> allocationFinishService.finish(event)).isInstanceOf(InvalidEventException.class);
        assertThat(processedCount(event.eventId())).isZero();
    }

    @Test
    @DisplayName("배정 전체 수량이 일반청약 물량보다 많아도 ALLOCATED가 되고 에러 로그를 남긴다")
    void finish_allocatesAndAlerts_whenTotalExceedsRetailQuantity(CapturedOutput output) {
        // given
        AllocationFinished event = event(UUID.randomUUID(), RETAIL_QUANTITY + 1, 0);

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event);

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.ALLOCATED);
        assertThat(ipoStatus()).isEqualTo("ALLOCATED");
        assertThat(output.getOut()).contains(QUANTITY_ALERT).doesNotContain(COUNT_ALERT);
    }

    @Test
    @DisplayName("배정 청약 건수가 확정 청약 수와 달라도 ALLOCATED가 되고 에러 로그를 남긴다")
    void finish_allocatesAndAlerts_whenCountDiffers(CapturedOutput output) {
        // given
        insertSubscription("CONFIRMED");
        insertSubscription("CONFIRMED");
        AllocationFinished event = event(UUID.randomUUID(), RETAIL_QUANTITY, 1);

        // when
        AllocationFinishedResult result = allocationFinishService.finish(event);

        // then
        assertThat(result).isEqualTo(AllocationFinishedResult.ALLOCATED);
        assertThat(ipoStatus()).isEqualTo("ALLOCATED");
        assertThat(output.getOut()).contains(COUNT_ALERT).doesNotContain(QUANTITY_ALERT);
    }

    private AllocationFinished event(UUID eventId, long totalAllocatedQuantity, long subscriptionCount) {
        return new AllocationFinished(eventId, ipoId, totalAllocatedQuantity, subscriptionCount);
    }

    private void insertSubscription(String status) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 0, now(), now() - interval '2 day',
                        0, now(), 'test', now(), 'test')""",
                UUID.randomUUID(), UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), status);
    }

    private String ipoStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM ipo WHERE ipo_id = ?", String.class, ipoId);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ? AND consumer_name = ?",
                Integer.class, eventId, IpoAllocationFinishService.CONSUMER_NAME);
    }
}
