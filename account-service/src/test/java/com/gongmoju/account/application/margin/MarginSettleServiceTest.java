package com.gongmoju.account.application.margin;

import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 PostgreSQL(Testcontainers)에서 증거금 정산의 margin 상태별 처리와 DLT 대상 롤백을 확인한다. Docker가 필요하다
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
class MarginSettleServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private MarginSettleService marginSettleService;

    @Autowired
    private MarginReserveService marginReserveService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    // 예수금 20,000원으로 시작해서 15,000원을 인출해 둔다 (예수금 5,000 / 청약증거금 15,000)
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = insertAccount(userId, "100000000001");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event");
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM ledger_entry");
        jdbcTemplate.update("DELETE FROM margin");
        jdbcTemplate.update("DELETE FROM margin_void_intent");
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void 배정되면_배정_대금은_차감하고_나머지는_예수금으로_환불한다() {
        UUID subscriptionId = reserve(15_000);

        marginSettleService.settle(command(subscriptionId, accountId, 6_000));

        assertBalance(14_000, 0);
        Map<String, Object> margin = margin(subscriptionId);
        assertThat(margin.get("status")).isEqualTo("POSTED");
        assertThat(margin.get("posted_amount")).isEqualTo(6_000L);
        assertThat(margin.get("settled_at")).isNotNull();

        // 원장: 차감 후 청약증거금 9,000 → 환불 후 예수금 14,000
        List<Map<String, Object>> ledger = settleLedger();
        assertThat(ledger).extracting(row -> row.get("type")).containsExactly("MARGIN_POST", "REFUND");
        assertThat(ledger.get(0)).containsEntry("amount", 6_000L)
                .containsEntry("cash_balance_after", 5_000L).containsEntry("margin_balance_after", 9_000L);
        assertThat(ledger.get(1)).containsEntry("amount", 9_000L)
                .containsEntry("cash_balance_after", 14_000L).containsEntry("margin_balance_after", 0L);

        List<Map<String, Object>> settled = settledOutbox();
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).get("aggregate_id")).isEqualTo(margin.get("margin_id"));
        assertThat(settled.get(0).get("message_key")).isEqualTo(accountId.toString());
        assertThat(settled.get(0).get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(settled.get(0).get("posted_amount")).isEqualTo(6_000L);
        assertThat(settled.get(0).get("refund_amount")).isEqualTo(9_000L);
    }

    @Test
    void 전액_배정이면_환불_원장은_남기지_않는다() {
        UUID subscriptionId = reserve(15_000);

        marginSettleService.settle(command(subscriptionId, accountId, 15_000));

        assertBalance(5_000, 0);
        assertThat(settleLedger()).extracting(row -> row.get("type")).containsExactly("MARGIN_POST");
        assertThat(settledOutbox().get(0)).containsEntry("posted_amount", 15_000L).containsEntry("refund_amount", 0L);
    }

    @Test
    void 영주_배정이면_전액_반환하고_VOIDED로_남는다() {
        UUID subscriptionId = reserve(15_000);

        marginSettleService.settle(command(subscriptionId, accountId, 0));

        assertBalance(20_000, 0);
        Map<String, Object> margin = margin(subscriptionId);
        assertThat(margin.get("status")).isEqualTo("VOIDED");
        assertThat(margin.get("posted_amount")).isNull();

        List<Map<String, Object>> ledger = settleLedger();
        assertThat(ledger).extracting(row -> row.get("type")).containsExactly("MARGIN_VOID");
        assertThat(ledger.get(0)).containsEntry("amount", 15_000L)
                .containsEntry("cash_balance_after", 20_000L).containsEntry("margin_balance_after", 0L);

        assertThat(settledOutbox().get(0)).containsEntry("posted_amount", 0L).containsEntry("refund_amount", 15_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE topic = 'margin.voided'", Long.class)).isZero();
    }

    @Test
    void 이미_POSTED이고_배정_대금이_같으면_돈은_안_옮기고_같은_응답을_다시_보낸다() {
        UUID subscriptionId = reserve(15_000);
        marginSettleService.settle(command(subscriptionId, accountId, 6_000));

        marginSettleService.settle(command(subscriptionId, accountId, 6_000)); // 새 eventId

        assertBalance(14_000, 0);
        assertThat(settleLedger()).hasSize(2);
        assertThat(settledOutbox()).extracting(row -> row.get("refund_amount")).containsExactly(9_000L, 9_000L);
    }

    @Test
    void 이미_POSTED인데_배정_대금이_다르면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);
        marginSettleService.settle(command(subscriptionId, accountId, 6_000));

        assertThatThrownBy(() -> marginSettleService.settle(command(subscriptionId, accountId, 7_000)))
                .isInstanceOf(InvalidEventException.class);

        assertBalance(14_000, 0);
        assertThat(settleLedger()).hasSize(2);
        assertThat(settledOutbox()).hasSize(1);
        assertThat(settleProcessedCount()).isEqualTo(1L);
    }

    @Test
    void 영주로_반환된_뒤_영주_재요청이면_같은_응답을_다시_보낸다() {
        UUID subscriptionId = reserve(15_000);
        marginSettleService.settle(command(subscriptionId, accountId, 0));

        marginSettleService.settle(command(subscriptionId, accountId, 0)); // 새 eventId

        assertBalance(20_000, 0);
        assertThat(settleLedger()).hasSize(1);
        assertThat(settledOutbox()).extracting(row -> row.get("refund_amount")).containsExactly(15_000L, 15_000L);
    }

    @Test
    void 이미_VOIDED인데_배정_대금이_있으면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);
        marginSettleService.settle(command(subscriptionId, accountId, 0));

        assertThatThrownBy(() -> marginSettleService.settle(command(subscriptionId, accountId, 6_000)))
                .isInstanceOf(InvalidEventException.class);

        assertBalance(20_000, 0);
        assertThat(settleLedger()).hasSize(1);
        assertThat(settledOutbox()).hasSize(1);
        assertThat(settleProcessedCount()).isEqualTo(1L);
    }

    @Test
    void 같은_eventId가_다시_오면_아무것도_하지_않는다() {
        UUID subscriptionId = reserve(15_000);
        SettleMarginCommand command = command(subscriptionId, accountId, 6_000);

        marginSettleService.settle(command);
        marginSettleService.settle(command);

        assertBalance(14_000, 0);
        assertThat(settleLedger()).hasSize(2);
        assertThat(settledOutbox()).hasSize(1);
    }

    @Test
    void margin이_없으면_예외로_롤백한다() {
        assertThatThrownBy(() -> marginSettleService.settle(command(UUID.randomUUID(), accountId, 6_000)))
                .isInstanceOf(InvalidEventException.class);

        assertBalance(20_000, 0);
        assertRolledBack();
    }

    @Test
    void 계좌가_없으면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);

        assertThatThrownBy(() -> marginSettleService.settle(command(subscriptionId, UUID.randomUUID(), 6_000)))
                .isInstanceOf(InvalidEventException.class);

        assertPendingUntouched(subscriptionId);
    }

    @Test
    void margin_계좌와_요청_계좌가_다르면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);
        UUID otherAccountId = insertAccount(UUID.randomUUID(), "100000000002");

        assertThatThrownBy(() -> marginSettleService.settle(command(subscriptionId, otherAccountId, 6_000)))
                .isInstanceOf(InvalidEventException.class);

        assertPendingUntouched(subscriptionId);
    }

    @Test
    void 배정_대금이_증거금보다_크면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);

        assertThatThrownBy(() -> marginSettleService.settle(command(subscriptionId, accountId, 15_001)))
                .isInstanceOf(InvalidEventException.class);

        assertPendingUntouched(subscriptionId);
    }

    // 인출 상태 그대로 (예수금 5,000 / 청약증거금 15,000, PENDING) 정산 쪽 기록이 없어야 한다
    private void assertPendingUntouched(UUID subscriptionId) {
        assertBalance(5_000, 15_000);
        assertThat(margin(subscriptionId).get("status")).isEqualTo("PENDING");
        assertRolledBack();
    }

    // 정산 쪽 이벤트 처리 기록, 원장, 응답이 없어야 한다
    private void assertRolledBack() {
        assertThat(settleProcessedCount()).isZero();
        assertThat(settleLedger()).isEmpty();
        assertThat(settledOutbox()).isEmpty();
    }

    // 인출 서비스로 PENDING margin을 만든다
    private UUID reserve(long amount) {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, UUID.randomUUID(), amount));
        return subscriptionId;
    }

    private SettleMarginCommand command(UUID subscriptionId, UUID accountId, long allocatedAmount) {
        return new SettleMarginCommand(UUID.randomUUID(), subscriptionId, accountId, allocatedAmount);
    }

    private UUID insertAccount(UUID ownerId, String accountNumber) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                ownerId, "u" + ownerId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 20000, 0, now(), now(), 'test', 'test')""",
                id, ownerId, UUID.randomUUID(), accountNumber);
        return id;
    }

    private void assertBalance(long cash, long margin) {
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account.get("cash_balance")).isEqualTo(cash);
        assertThat(account.get("margin_balance")).isEqualTo(margin);
    }

    private Map<String, Object> margin(UUID subscriptionId) {
        return jdbcTemplate.queryForMap(
                "SELECT margin_id, status, posted_amount, settled_at FROM margin WHERE subscription_id = ?", subscriptionId);
    }

    // 인출 원장(MARGIN_PENDING)을 뺀 정산 쪽 원장
    private List<Map<String, Object>> settleLedger() {
        return jdbcTemplate.queryForList("""
                SELECT type, amount, cash_balance_after, margin_balance_after
                FROM ledger_entry WHERE type <> 'MARGIN_PENDING' ORDER BY created_at, type""");
    }

    private List<Map<String, Object>> settledOutbox() {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_id, message_key, payload->>'subscriptionId' AS subscription_id,
                       (payload->>'postedAmount')::bigint AS posted_amount,
                       (payload->>'refundAmount')::bigint AS refund_amount
                FROM outbox WHERE topic = 'margin.settled' ORDER BY created_at""");
    }

    private long settleProcessedCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processed_event WHERE consumer_name = ?", Long.class, MarginSettleService.CONSUMER_NAME);
    }
}
