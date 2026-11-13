package com.gongmoju.account.application.reconciliation;

import com.gongmoju.account.application.account.DepositService;
import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.MarginSettleService;
import com.gongmoju.account.application.margin.MarginVoidService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.application.margin.SettleMarginCommand;
import com.gongmoju.account.application.margin.VoidMarginCommand;
import com.gongmoju.account.domain.margin.MarginVoidReason;
import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
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

import java.util.UUID;

import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_SUM;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_BALANCE;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_PENDING_LEDGER;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_STATUS_LEDGER;
import static org.assertj.core.api.Assertions.assertThat;

// 실제 PostgreSQL(Testcontainers)에서 원장 대사 검사 4개가 정상 흐름은 통과시키고, 불일치는 해당 검사로 검출하는지 확인한다. Docker가 필요하다
// 불일치는 서비스 메서드를 거치지 않고 SQL로 직접 생성한다 (실제로는 DB 직접 수정, 버그 코드로 발생하는 상황)
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
class LedgerReconciliationServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private MarginReserveService marginReserveService;

    @Autowired
    private MarginSettleService marginSettleService;

    @Autowired
    private MarginVoidService marginVoidService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;
    private UUID ipoId;
    private UUID postedSubscriptionId;
    private UUID voidedSubscriptionId;
    private UUID pendingSubscriptionId;

    // 정상 흐름: 입금 100,000 → 인출 30,000, 20,000, 15,000 → 첫 청약 정산(배정 대금 10,000, 환불 20,000) → 두 번째 청약 반환
    // 결과: 예수금 75,000 / 청약증거금 15,000 (PENDING 증거금 15,000)
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = insertEmptyAccount(userId);
        ipoId = UUID.randomUUID();

        depositService.deposit(accountId, userId, 100_000, UUID.randomUUID());
        postedSubscriptionId = reserve(30_000);
        voidedSubscriptionId = reserve(20_000);
        pendingSubscriptionId = reserve(15_000);
        marginSettleService.settle(new SettleMarginCommand(UUID.randomUUID(), postedSubscriptionId, accountId, ipoId, 10_000));
        marginVoidService.voidMargin(new VoidMarginCommand(UUID.randomUUID(), voidedSubscriptionId, accountId, MarginVoidReason.USER_CANCEL));
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
    void 입금_인출_정산_반환을_정상_처리하면_불일치가_없다() {
        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.consistent()).isTrue();
    }

    @Test
    void 정산_원장_2줄의_기록_시각이_같아도_불일치로_검출하지_않는다() {
        // 정산 1건의 MARGIN_POST, REFUND 원장을 동일한 시각으로 변경한다. 마지막 원장 방식이면 둘 중 무엇을 마지막으로 선택할지 판단 불가능한 상황
        jdbcTemplate.update("""
                UPDATE ledger_entry SET created_at = TIMESTAMPTZ '2026-11-14 10:00:00+09'
                WHERE type IN ('MARGIN_POST', 'REFUND')""");

        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void 원장_없이_예수금만_차감하면_잔고_합계_검사가_검출한다() {
        jdbcTemplate.update("UPDATE account SET cash_balance = cash_balance - 10000 WHERE account_id = ?", accountId);

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.mismatches()).extracting(LedgerMismatch::check).containsExactly(LEDGER_SUM);
        LedgerMismatch mismatch = result.mismatches().get(0);
        assertThat(mismatch.accountId()).isEqualTo(accountId);
        assertThat(mismatch.detail()).isEqualTo("예수금=65000, 원장 예수금 합계=75000, 청약증거금=15000, 원장 청약증거금 합계=15000");
    }

    @Test
    void 원장_없이_증거금을_반환_상태로_변경하면_청약증거금_검사와_상태별_원장_검사가_검출한다() {
        jdbcTemplate.update("UPDATE margin SET status = 'VOIDED' WHERE subscription_id = ?", pendingSubscriptionId);

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.mismatches()).extracting(LedgerMismatch::check)
                .containsExactlyInAnyOrder(MARGIN_BALANCE, MARGIN_STATUS_LEDGER);
        assertThat(result.count(MARGIN_STATUS_LEDGER)).isEqualTo(1);
        assertThat(result.mismatches()).filteredOn(m -> m.check() == MARGIN_STATUS_LEDGER)
                .extracting(LedgerMismatch::marginId).containsExactly(marginId(pendingSubscriptionId));
    }

    @Test
    void 인출_원장이_중복되면_인출_원장_검사와_잔고_합계_검사가_검출한다() {
        UUID marginId = marginId(pendingSubscriptionId);
        jdbcTemplate.update("""
                INSERT INTO ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after,
                                          margin_id, subscription_id, created_at, created_by)
                VALUES (?, ?, 'MARGIN_PENDING', 15000, 60000, 30000, ?, ?, now(), 'test')""",
                UUID.randomUUID(), accountId, marginId, pendingSubscriptionId);

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.mismatches()).extracting(LedgerMismatch::check)
                .containsExactlyInAnyOrder(MARGIN_PENDING_LEDGER, LEDGER_SUM);
        assertThat(result.mismatches()).filteredOn(m -> m.check() == MARGIN_PENDING_LEDGER).singleElement()
                .satisfies(m -> {
                    assertThat(m.marginId()).isEqualTo(marginId);
                    assertThat(m.detail()).isEqualTo("증거금=15000, 인출 원장 2줄, 인출 원장 합계=30000");
                });
    }

    @Test
    void 인출_원장이_2줄로_분할되면_합계가_증거금과_동일해도_인출_원장_검사가_검출한다() {
        // 15,000 인출 원장을 5,000 + 10,000 2줄로 분할한다. 금액 합계와 잔고 합계는 변경되지 않아 줄 수 조건만 검출한다
        UUID marginId = marginId(pendingSubscriptionId);
        jdbcTemplate.update("UPDATE ledger_entry SET amount = 5000 WHERE margin_id = ? AND type = 'MARGIN_PENDING'", marginId);
        jdbcTemplate.update("""
                INSERT INTO ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after,
                                          margin_id, subscription_id, created_at, created_by)
                VALUES (?, ?, 'MARGIN_PENDING', 10000, 35000, 65000, ?, ?, now(), 'test')""",
                UUID.randomUUID(), accountId, marginId, pendingSubscriptionId);

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.check()).isEqualTo(MARGIN_PENDING_LEDGER);
            assertThat(m.detail()).isEqualTo("증거금=15000, 인출 원장 2줄, 인출 원장 합계=15000");
        });
    }

    @Test
    void 환불_원장_금액이_다르면_상태별_원장_검사와_잔고_합계_검사가_검출한다() {
        jdbcTemplate.update("UPDATE ledger_entry SET amount = amount - 1000 WHERE type = 'REFUND'");

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.mismatches()).extracting(LedgerMismatch::check)
                .containsExactlyInAnyOrder(MARGIN_STATUS_LEDGER, LEDGER_SUM);
        assertThat(result.mismatches()).filteredOn(m -> m.check() == MARGIN_STATUS_LEDGER).singleElement()
                .extracting(LedgerMismatch::detail)
                .isEqualTo("상태=POSTED, 증거금=30000, posted_amount=10000, 정산 원장=10000, 환불 원장=19000, 반환 원장=0");
    }

    @Test
    void 불일치_건수를_검사별로_집계한다() {
        jdbcTemplate.update("UPDATE account SET cash_balance = cash_balance - 10000 WHERE account_id = ?", accountId);

        LedgerReconciliationResult result = reconciliationService.reconcile();

        assertThat(result.count(LEDGER_SUM)).isEqualTo(1);
        assertThat(result.count(MARGIN_BALANCE)).isZero();
        assertThat(result.count(MARGIN_PENDING_LEDGER)).isZero();
        assertThat(result.count(MARGIN_STATUS_LEDGER)).isZero();
    }

    private UUID reserve(long amount) {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, ipoId, amount));
        return subscriptionId;
    }

    // 잔고 0인 계좌. 잔고는 입금 서비스로만 변경해서 원장과 일치시킨다
    private UUID insertEmptyAccount(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                ownerId, "u" + ownerId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, '100000000001', 0, 0, now(), now(), 'test', 'test')""",
                id, ownerId, UUID.randomUUID());
        return id;
    }

    private UUID marginId(UUID subscriptionId) {
        return jdbcTemplate.queryForObject("SELECT margin_id FROM margin WHERE subscription_id = ?", UUID.class, subscriptionId);
    }
}
