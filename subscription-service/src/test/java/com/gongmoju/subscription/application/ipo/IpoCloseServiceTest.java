package com.gongmoju.subscription.application.ipo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공모주 마감(진행 중 청약 확인, OPEN → CLOSED, ipo.closed Outbox 기록)을 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("공모주 마감 처리 (PostgreSQL)")
class IpoCloseServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private IpoCloseService closeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private static final String INSERT_SUBSCRIPTION = """
            INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                      margin_amount, status, attempts, last_requested_at, received_at,
                                      version, created_at, created_by, updated_at, updated_by)
            VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 0, now(), '2026-10-27T06:00:00Z',
                    0, now(), 'test', now(), 'test')""";

    private UUID ipoId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 접수 Consumer만 사용) SQL로 직접 저장함.
     * 마감 시각은 2026-10-27 16:00 KST(07:00Z)로 고정해서 closedAt 형식을 확인함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        '2026-10-26T01:00:00Z', '2026-10-27T07:00:00Z', '2026-10-29', 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("진행 중 청약이 없으면 CLOSED가 되고 ipo.closed가 IPO 집계, ipoId 키로 Outbox에 기록된다")
    void close_closesIpoAndRecordsIpoClosed() throws Exception {
        // given
        insertSubscription("CONFIRMED");
        insertSubscription("CONFIRMED");
        insertSubscription("REJECTED");
        insertSubscription("CANCELLED");

        // when
        IpoCloseResult result = closeService.close(ipoId);

        // then
        assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.CLOSED);
        assertThat(result.confirmedCount()).isEqualTo(2);
        assertThat(ipoStatus()).isEqualTo("CLOSED");

        List<Map<String, Object>> rows = outboxRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("aggregate_type")).isEqualTo("IPO");
        assertThat(row.get("topic")).isEqualTo("ipo.closed");
        assertThat(row.get("event_type")).isEqualTo("ipo.closed");
        assertThat(row.get("message_key")).isEqualTo(ipoId.toString());
        assertThat(row.get("published_at")).isNull();

        JsonNode payload = objectMapper.readTree(row.get("payload").toString());
        assertThat(payload.get("ipoId").asText()).isEqualTo(ipoId.toString());
        assertThat(payload.get("closedAt").asText()).isEqualTo("2026-10-27T16:00:00.000+09:00");
        assertThat(payload.get("confirmedCount").asLong()).isEqualTo(2);
        assertThat(payload.get("offerPrice").asLong()).isEqualTo(15000);
        assertThat(payload.get("retailQuantity").asLong()).isEqualTo(100000);
        assertThat(payload.get("equalQuantity").asLong()).isEqualTo(50000);
        assertThat(payload.get("minQuantity").asLong()).isEqualTo(10);
        assertThat(payload.size()).isEqualTo(7);
    }

    @Test
    @DisplayName("청약이 하나도 없어도 마감하고 confirmedCount는 0이다")
    void close_closesWithZeroConfirmed_whenNoSubscriptions() throws Exception {
        // when
        IpoCloseResult result = closeService.close(ipoId);

        // then
        assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.CLOSED);
        assertThat(ipoStatus()).isEqualTo("CLOSED");
        JsonNode payload = objectMapper.readTree(outboxRows().get(0).get("payload").toString());
        assertThat(payload.get("confirmedCount").asLong()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PENDING", "COMPENSATING", "CANCEL_REQUESTED"})
    @DisplayName("진행 중 청약(PENDING, COMPENSATING, CANCEL_REQUESTED)이 있으면 마감하지 않고 Outbox에 기록하지 않는다")
    void close_doesNotClose_whenSubscriptionInProgress(String status) {
        // given
        insertSubscription("CONFIRMED");
        insertSubscription(status);

        // when
        IpoCloseResult result = closeService.close(ipoId);

        // then
        assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.IN_PROGRESS_REMAINING);
        assertThat(result.pendingCount() + result.compensatingCount() + result.cancelRequestedCount()).isEqualTo(1);
        assertThat(ipoStatus()).isEqualTo("OPEN");
        assertThat(outboxRows()).isEmpty();
    }

    @Test
    @DisplayName("진행 중 청약 건수를 상태별로 돌려준다")
    void close_returnsInProgressCountsByStatus() {
        // given
        insertSubscription("PENDING");
        insertSubscription("PENDING");
        insertSubscription("COMPENSATING");
        insertSubscription("CANCEL_REQUESTED");

        // when
        IpoCloseResult result = closeService.close(ipoId);

        // then
        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.compensatingCount()).isEqualTo(1);
        assertThat(result.cancelRequestedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("공모주 행을 잡은 트랜잭션이 끝날 때까지 기다렸다가, 그 트랜잭션이 저장한 PENDING을 세어 마감하지 않는다")
    void close_waitsForTransactionHoldingIpoRow_andCountsItsPending() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            // given: 다른 트랜잭션이 공모주 행을 FOR SHARE로 잡고 PENDING을 저장한 뒤 아직 커밋하지 않음
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT ipo_id FROM ipo WHERE ipo_id = ? FOR SHARE")) {
                lock.setObject(1, ipoId);
                lock.executeQuery().close();
            }
            try (PreparedStatement insert = holder.prepareStatement(INSERT_SUBSCRIPTION)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, UUID.randomUUID());
                insert.setObject(3, ipoId);
                insert.setObject(4, UUID.randomUUID());
                insert.setObject(5, UUID.randomUUID());
                insert.setString(6, "PENDING");
                insert.executeUpdate();
            }

            // when: 마감은 공모주 행 잠금을 기다림
            Future<IpoCloseResult> closing = executor.submit(() -> closeService.close(ipoId));
            Thread.sleep(1000);
            assertThat(closing).isNotDone();
            holder.commit();
            IpoCloseResult result = closing.get(10, TimeUnit.SECONDS);

            // then: 커밋된 PENDING을 세어 마감하지 않음
            assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.IN_PROGRESS_REMAINING);
            assertThat(result.pendingCount()).isEqualTo(1);
            assertThat(ipoStatus()).isEqualTo("OPEN");
            assertThat(outboxRows()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("이미 CLOSED인 공모주는 아무것도 하지 않고 NOT_OPEN으로 끝난다")
    void close_doesNothing_whenAlreadyClosed() {
        // given
        closeService.close(ipoId);

        // when
        IpoCloseResult result = closeService.close(ipoId);

        // then
        assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.NOT_OPEN);
        assertThat(outboxRows()).hasSize(1);
    }

    @Test
    @DisplayName("없는 공모주면 IllegalStateException")
    void close_throws_whenIpoMissing() {
        // when, then
        assertThatThrownBy(() -> closeService.close(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    private void insertSubscription(String status) {
        jdbcTemplate.update(INSERT_SUBSCRIPTION,
                UUID.randomUUID(), UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), status);
    }

    private String ipoStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM ipo WHERE ipo_id = ?", String.class, ipoId);
    }

    private List<Map<String, Object>> outboxRows() {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_type, topic, event_type, message_key, payload, published_at
                FROM outbox WHERE aggregate_id = ?""", ipoId);
    }
}
