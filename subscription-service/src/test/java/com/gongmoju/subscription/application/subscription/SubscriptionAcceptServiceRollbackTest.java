package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 증거금 인출 요청 기록이 실패하면 청약 저장도 함께 롤백되는지 검증함 (같은 트랜잭션). 기록기를 실패하도록 대체하므로 별도 클래스로 분리함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("접수 처리 롤백 (PostgreSQL)")
class SubscriptionAcceptServiceRollbackTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @MockitoBean
    private MarginReserveRequestRecorder marginReserveRequestRecorder;

    @Autowired
    private SubscriptionAcceptService acceptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;

    /**
     * 공모주 생성 메서드가 없으므로(시드 데이터만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
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
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("증거금 인출 요청 기록이 실패하면 예외가 전파되고 PENDING 청약도 저장되지 않는다")
    void accept_rollsBackSubscription_whenRecordingFails() {
        // given
        UUID userId = UUID.randomUUID();
        SubscribeCommand command = new SubscribeCommand(UUID.randomUUID(), ipoId, UUID.randomUUID(), 100);
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        doThrow(new IllegalStateException("기록 실패")).when(marginReserveRequestRecorder).record(any());

        // when, then
        assertThatThrownBy(() -> acceptService.accept(SubscriptionIdGenerator.generate(userId, command.requestId()), userId, receivedAt, command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("기록 실패");
        Integer saved = jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ?", Integer.class, ipoId);
        assertThat(saved).isZero();
    }
}
