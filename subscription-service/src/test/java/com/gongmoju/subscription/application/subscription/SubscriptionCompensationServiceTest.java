package com.gongmoju.subscription.application.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 보상 착수(청약 {@code COMPENSATING} 전환, {@code margin.void-requested} Outbox 기록)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("보상 착수 (PostgreSQL)")
class SubscriptionCompensationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionCompensationService compensationService;

    @MockitoSpyBean
    private MarginVoidRequestRecorder marginVoidRequestRecorder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID ipoId;
    private UUID subscriptionId;
    private UUID accountId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        insertSubscription("PENDING");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("PENDING 청약은 COMPENSATING(CONFIRM_FAILED)이 되고 요청 횟수 1로 바뀌며 margin.void-requested가 Outbox에 1건 기록된다")
    void startConfirmFailedCompensation_changesStatusAndRecordsOutbox() throws Exception {
        // given, when
        CompensationResult result = compensationService.startConfirmFailedCompensation(subscriptionId);

        // then
        assertThat(result).isEqualTo(CompensationResult.STARTED);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("COMPENSATING");
        assertThat(row.get("cancel_reason")).isEqualTo("CONFIRM_FAILED");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("version")).isEqualTo(1L);

        List<Map<String, Object>> outbox = outboxRows();
        assertThat(outbox).hasSize(1);
        Map<String, Object> message = outbox.get(0);
        assertThat(message.get("topic")).isEqualTo("margin.void-requested");
        assertThat(message.get("event_type")).isEqualTo("margin.void-requested");
        assertThat(message.get("aggregate_type")).isEqualTo("SUBSCRIPTION");
        assertThat(message.get("message_key")).isEqualTo(accountId.toString());
        assertThat(message.get("published_at")).isNull();
        JsonNode payload = objectMapper.readTree(message.get("payload").toString());
        assertThat(payload.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(payload.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(payload.get("reason").asText()).isEqualTo("CONFIRM_FAILED");
        assertThat(payload.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 청약에 두 번 호출해도 해제 요청은 1건만 기록되고 두 번째는 NOT_PENDING_IGNORED로 끝난다")
    void startConfirmFailedCompensation_recordsOnce_whenCalledTwice() {
        // given
        compensationService.startConfirmFailedCompensation(subscriptionId);

        // when
        CompensationResult second = compensationService.startConfirmFailedCompensation(subscriptionId);

        // then
        assertThat(second).isEqualTo(CompensationResult.NOT_PENDING_IGNORED);
        assertThat(outboxRows()).hasSize(1);
        assertThat(subscription().get("version")).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONFIRMED", "CANCEL_REQUESTED", "CANCELLED", "REJECTED", "ALLOCATED", "REFUNDED"})
    @DisplayName("PENDING이 아닌 청약은 상태를 바꾸지 않고 해제 요청도 기록하지 않는다")
    void startConfirmFailedCompensation_ignores_whenNotPending(String status) {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = ? WHERE subscription_id = ?", status, subscriptionId);

        // when
        CompensationResult result = compensationService.startConfirmFailedCompensation(subscriptionId);

        // then
        assertThat(result).isEqualTo(CompensationResult.NOT_PENDING_IGNORED);
        assertThat(subscription().get("status")).isEqualTo(status);
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("이미 COMPENSATING인 청약은 다시 시작하지 않고 해제 요청도 늘지 않는다")
    void startConfirmFailedCompensation_ignores_whenAlreadyCompensating() {
        // given
        jdbcTemplate.update("UPDATE subscription SET status = 'COMPENSATING', cancel_reason = 'TIMEOUT' WHERE subscription_id = ?", subscriptionId);

        // when
        CompensationResult result = compensationService.startConfirmFailedCompensation(subscriptionId);

        // then
        assertThat(result).isEqualTo(CompensationResult.NOT_PENDING_IGNORED);
        assertThat(subscription().get("cancel_reason")).isEqualTo("TIMEOUT");
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId는 InvalidEventException이 나고 아무것도 기록되지 않는다")
    void startConfirmFailedCompensation_throws_whenSubscriptionNotFound() {
        // given
        UUID unknown = UUID.randomUUID();

        // when, then
        assertThatThrownBy(() -> compensationService.startConfirmFailedCompensation(unknown))
                .isInstanceOf(InvalidEventException.class);
        assertThat(subscription().get("status")).isEqualTo("PENDING");
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("Outbox 기록이 실패하면 상태 전환도 함께 롤백되어 PENDING으로 남고 Outbox에는 아무것도 없다")
    void startConfirmFailedCompensation_rollsBackStatus_whenOutboxRecordFails() {
        // given
        doThrow(new IllegalStateException("Outbox 기록 실패")).when(marginVoidRequestRecorder).record(any());

        // when, then
        assertThatThrownBy(() -> compensationService.startConfirmFailedCompensation(subscriptionId))
                .isInstanceOf(IllegalStateException.class);
        Map<String, Object> row = subscription();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("cancel_reason")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("version")).isEqualTo(0L);
        assertThat(outboxRows()).isEmpty();
    }

    private void insertSubscription(String status) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 1, now(), now() - interval '1 minute',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, status);
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, attempts, version FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList("""
                SELECT topic, event_type, aggregate_type, message_key, payload, published_at
                FROM outbox WHERE aggregate_id = ?""", subscriptionId);
    }
}
