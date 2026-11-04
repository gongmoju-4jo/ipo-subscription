package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.margin.MarginVoidReason;
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

// 실제 PostgreSQL(Testcontainers)에서 증거금 반환의 margin 상태별 처리와 DLT 대상 롤백을 확인한다. Docker가 필요하다
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
class MarginVoidServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private MarginVoidService marginVoidService;

    @Autowired
    private MarginReserveService marginReserveService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    // 예수금 20,000원으로 시작 (MarginReserveServiceTest와 같은 방식)
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
    void PENDING이면_청약증거금이_예수금으로_돌아오고_VOIDED와_원장이_남는다() {
        UUID subscriptionId = reserve(15_000);

        marginVoidService.voidMargin(command(subscriptionId, accountId));

        assertBalance(20_000, 0);
        Map<String, Object> margin = jdbcTemplate.queryForMap(
                "SELECT margin_id, status, settled_at FROM margin WHERE subscription_id = ?", subscriptionId);
        assertThat(margin.get("status")).isEqualTo("VOIDED");
        assertThat(margin.get("settled_at")).isNotNull();

        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT amount, cash_balance_after, margin_balance_after, margin_id FROM ledger_entry WHERE type = 'MARGIN_VOID'");
        assertThat(ledger.get("amount")).isEqualTo(15_000L);
        assertThat(ledger.get("cash_balance_after")).isEqualTo(20_000L);
        assertThat(ledger.get("margin_balance_after")).isEqualTo(0L);
        assertThat(ledger.get("margin_id")).isEqualTo(margin.get("margin_id"));

        List<Map<String, Object>> voided = voidedOutbox();
        assertThat(voided).hasSize(1);
        assertThat(voided.get(0).get("aggregate_id")).isEqualTo(margin.get("margin_id"));
        assertThat(voided.get(0).get("message_key")).isEqualTo(accountId.toString());
        assertThat(voided.get(0).get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(voided.get(0).get("amount")).isEqualTo(15_000L);
        assertThat(count("margin_void_intent")).isEqualTo(1L);
    }

    @Test
    void margin이_없으면_amount_0으로_응답하고_반환_기록을_남긴다() {
        UUID subscriptionId = UUID.randomUUID();

        marginVoidService.voidMargin(command(subscriptionId, accountId));

        assertBalance(20_000, 0);
        assertThat(count("ledger_entry")).isZero();
        List<Map<String, Object>> voided = voidedOutbox();
        assertThat(voided).hasSize(1);
        assertThat(voided.get(0).get("aggregate_id")).isEqualTo(subscriptionId);
        assertThat(voided.get(0).get("amount")).isEqualTo(0L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM margin_void_intent WHERE subscription_id = ?", String.class, subscriptionId))
                .isEqualTo("TIMEOUT");
    }

    @Test
    void 반환_기록이_있으면_뒤늦은_인출_요청은_돈을_옮기지_않는다() {
        UUID subscriptionId = UUID.randomUUID();
        marginVoidService.voidMargin(command(subscriptionId, accountId));

        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, UUID.randomUUID(), 15_000));

        assertBalance(20_000, 0);
        assertThat(count("margin")).isZero();
    }

    @Test
    void 계좌가_없으면_amount_0으로_응답하고_반환_기록은_남기지_않는다() {
        UUID subscriptionId = UUID.randomUUID();
        UUID unknownAccountId = UUID.randomUUID();

        marginVoidService.voidMargin(command(subscriptionId, unknownAccountId));

        List<Map<String, Object>> voided = voidedOutbox();
        assertThat(voided).hasSize(1);
        assertThat(voided.get(0).get("message_key")).isEqualTo(unknownAccountId.toString());
        assertThat(voided.get(0).get("amount")).isEqualTo(0L);
        assertThat(count("margin_void_intent")).isZero();
        assertThat(count("processed_event")).isEqualTo(1L);
    }

    @Test
    void 이미_VOIDED면_돈은_안_옮기고_같은_금액을_다시_보낸다() {
        UUID subscriptionId = reserve(15_000);
        marginVoidService.voidMargin(command(subscriptionId, accountId));

        marginVoidService.voidMargin(command(subscriptionId, accountId)); // 새 eventId

        assertBalance(20_000, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entry WHERE type = 'MARGIN_VOID'", Long.class)).isEqualTo(1L);
        assertThat(voidedOutbox()).extracting(row -> row.get("amount")).containsExactly(15_000L, 15_000L);
        assertThat(count("margin_void_intent")).isEqualTo(1L);
    }

    @Test
    void 같은_eventId가_다시_오면_아무것도_하지_않는다() {
        UUID subscriptionId = reserve(15_000);
        VoidMarginCommand command = command(subscriptionId, accountId);

        marginVoidService.voidMargin(command);
        marginVoidService.voidMargin(command);

        assertBalance(20_000, 0);
        assertThat(voidedOutbox()).hasSize(1);
    }

    @Test
    void 이미_POSTED면_예외로_롤백해서_아무것도_남기지_않는다() {
        UUID subscriptionId = reserve(15_000);
        jdbcTemplate.update("UPDATE margin SET status = 'POSTED', posted_amount = 10000, settled_at = now() WHERE subscription_id = ?", subscriptionId);

        assertThatThrownBy(() -> marginVoidService.voidMargin(command(subscriptionId, accountId)))
                .isInstanceOf(InvalidEventException.class);

        assertRolledBack();
    }

    @Test
    void margin_계좌와_요청_계좌가_다르면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);
        UUID otherAccountId = insertAccount(UUID.randomUUID(), "100000000002");

        assertThatThrownBy(() -> marginVoidService.voidMargin(command(subscriptionId, otherAccountId)))
                .isInstanceOf(InvalidEventException.class);

        assertRolledBack();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM margin WHERE subscription_id = ?", String.class, subscriptionId)).isEqualTo("PENDING");
    }

    @Test
    void 요청_계좌가_없는데_margin이_다른_계좌에_있으면_예외로_롤백한다() {
        UUID subscriptionId = reserve(15_000);
        UUID unknownAccountId = UUID.randomUUID();

        assertThatThrownBy(() -> marginVoidService.voidMargin(command(subscriptionId, unknownAccountId)))
                .isInstanceOf(InvalidEventException.class);

        assertRolledBack();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM margin WHERE subscription_id = ?", String.class, subscriptionId)).isEqualTo("PENDING");
    }

    // 인출 처리 기록 1건만 남고 반환 쪽 기록, 잔고 변화, 응답이 없어야 한다
    private void assertRolledBack() {
        assertBalance(5_000, 15_000);
        assertThat(count("processed_event")).isEqualTo(1L);
        assertThat(count("margin_void_intent")).isZero();
        assertThat(voidedOutbox()).isEmpty();
    }

    // 인출 서비스로 PENDING margin을 만든다
    private UUID reserve(long amount) {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, UUID.randomUUID(), amount));
        return subscriptionId;
    }

    private VoidMarginCommand command(UUID subscriptionId, UUID accountId) {
        return new VoidMarginCommand(UUID.randomUUID(), subscriptionId, accountId, MarginVoidReason.TIMEOUT);
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

    private List<Map<String, Object>> voidedOutbox() {
        return jdbcTemplate.queryForList("""
                SELECT aggregate_id, message_key, payload->>'subscriptionId' AS subscription_id,
                       (payload->>'amount')::bigint AS amount
                FROM outbox WHERE topic = 'margin.voided' ORDER BY created_at""");
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
