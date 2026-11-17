package com.gongmoju.account.application.margin;

import com.gongmoju.account.global.exception.InvalidEventException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 여러 건을 한 트랜잭션으로 처리하는 reserveAll (#162)을 실제 PostgreSQL(Testcontainers)에서 확인한다. Docker가 필요하다
// 건별 처리 본문은 reserve와 같아서, 건별 경우의 상세 검증은 MarginReserveServiceTest가 맡는다
// hibernate.generate_statistics: 준비한 SQL 수로 JDBC 배치가 적용됐는지 확인하려고 이 테스트에서만 켠다
@DisplayName("증거금 인출 배치 처리(reserveAll)")
@Testcontainers
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"})
class MarginReserveBatchServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private MarginReserveService marginReserveService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private UUID userId;
    private UUID accountId;
    private UUID ipoId;

    // 예수금 20,000원 계좌 하나로 시작한다
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ipoId = UUID.randomUUID();
        insertAccount(userId, accountId, "100000000001", 20_000);
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
    @DisplayName("여러 계좌의 정상 요청은 모두 인출하고 건마다 처리 기록이 1개씩 남는다")
    void reserveAll_multipleAccounts_reservesAllAndRecordsEachEvent() {
        // given
        UUID otherUserId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        insertAccount(otherUserId, otherAccountId, "100000000002", 30_000);
        List<ReserveMarginCommand> commands = List.of(
                command(accountId, userId, UUID.randomUUID(), 15_000),
                command(otherAccountId, otherUserId, UUID.randomUUID(), 10_000));

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(2, 0, 0));
        assertThat(cashBalance(accountId)).isEqualTo(5_000L);
        assertThat(cashBalance(otherAccountId)).isEqualTo(20_000L);
        assertThat(count("margin")).isEqualTo(2);
        assertThat(count("ledger_entry")).isEqualTo(2);
        assertThat(count("processed_event")).isEqualTo(2);
        assertThat(count("outbox WHERE event_type = 'margin.reserved'")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 계좌 3건에 예수금이 2건분이면 2건은 인출하고 3번째는 margin.reserve-failed이며 원장 잔고가 순서대로 이어진다")
    void reserveAll_sameAccountInsufficientForThird_rejectsThirdAndKeepsLedgerSequential() {
        // given: 예수금 20,000원, 건당 10,000원
        List<ReserveMarginCommand> commands = List.of(
                command(UUID.randomUUID(), 10_000),
                command(UUID.randomUUID(), 10_000),
                command(UUID.randomUUID(), 10_000));

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(2, 1, 0));
        assertThat(cashBalance(accountId)).isZero();
        assertThat(count("margin")).isEqualTo(2);
        // 앞 건의 잔고 변경이 뒤 건에 보여서 거래 후 잔고가 10,000 → 0, 0 → 10,000으로 이어진다
        List<Map<String, Object>> ledger = jdbcTemplate.queryForList(
                "SELECT cash_balance_after, margin_balance_after FROM ledger_entry ORDER BY margin_balance_after");
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0)).containsEntry("cash_balance_after", 10_000L).containsEntry("margin_balance_after", 10_000L);
        assertThat(ledger.get(1)).containsEntry("cash_balance_after", 0L).containsEntry("margin_balance_after", 20_000L);
        assertThat(count("outbox WHERE event_type = 'margin.reserved'")).isEqualTo(2);
        assertThat(count("outbox WHERE event_type = 'margin.reserve-failed'")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 계좌의 요청 30건을 메모리에서 이어서 판단해도 잔고와 원장이 정확히 누적된다")
    void reserveAll_manyRequestsOnSameAccount_accumulatesBalanceCorrectly() {
        // given: 예수금 20,000원, 100원씩 30건
        List<ReserveMarginCommand> commands = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            commands.add(command(UUID.randomUUID(), 100));
        }

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(30, 0, 0));
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account).containsEntry("cash_balance", 17_000L).containsEntry("margin_balance", 3_000L);
        assertThat(count("ledger_entry")).isEqualTo(30);
    }

    @Test
    @DisplayName("같은 청약의 요청이 eventId만 다르게 두 번 들어오면 margin과 원장은 1개이고 margin.reserved는 2개다")
    void reserveAll_sameSubscriptionTwice_reservesOnceAndRepublishes() {
        // given
        UUID subscriptionId = UUID.randomUUID();
        List<ReserveMarginCommand> commands = List.of(
                command(subscriptionId, 15_000),
                command(subscriptionId, 15_000));

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then: 두 번째는 같은 배치에서 만든 margin을 읽어 재발행 경로로 간다
        assertThat(result).isEqualTo(new ReserveBatchResult(1, 0, 1));
        assertThat(cashBalance(accountId)).isEqualTo(5_000L);
        assertThat(count("margin")).isEqualTo(1);
        assertThat(count("ledger_entry")).isEqualTo(1);
        assertThat(count("outbox WHERE event_type = 'margin.reserved'")).isEqualTo(2);
        assertThat(count("processed_event")).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 eventId가 한 배치에 두 번 있으면 한 번만 처리한다")
    void reserveAll_sameEventIdTwice_processesOnce() {
        // given
        ReserveMarginCommand command = command(UUID.randomUUID(), 15_000);

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(List.of(command, command));

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(1, 0, 1));
        assertThat(cashBalance(accountId)).isEqualTo(5_000L);
        assertThat(count("margin")).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("outbox WHERE event_type = 'margin.reserved'")).isEqualTo(1);
    }

    @Test
    @DisplayName("가운데 건이 기존 증거금과 금액이 다르면 예외가 나고 앞 건을 포함해 이번 배치로 저장된 것이 없다")
    void reserveAll_middleRequestConflictsWithExistingMargin_rollsBackWholeBatch() {
        // given: 먼저 처리해서 커밋된 청약 (10,000원)
        UUID existingSubscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(existingSubscriptionId, 10_000));
        List<ReserveMarginCommand> commands = List.of(
                command(UUID.randomUUID(), 3_000),
                command(existingSubscriptionId, 9_000), // 금액이 다른 재요청
                command(UUID.randomUUID(), 2_000));

        // when, then
        assertThatThrownBy(() -> marginReserveService.reserveAll(commands)).isInstanceOf(InvalidEventException.class);
        assertThat(cashBalance(accountId)).isEqualTo(10_000L);
        assertThat(count("margin")).isEqualTo(1);
        assertThat(count("ledger_entry")).isEqualTo(1);
        // 이번 배치의 처리 기록도 함께 롤백돼서 다시 처리할 수 있다
        assertThat(count("processed_event")).isEqualTo(1);
        assertThat(count("outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("선행 반환 기록이 있는 건이 섞여 있으면 그 건만 인출 없이 처리 기록만 남고 나머지는 인출한다")
    void reserveAll_requestWithVoidIntent_skipsOnlyThatRequest() {
        // given
        UUID voidedSubscriptionId = UUID.randomUUID();
        insertVoidIntent(voidedSubscriptionId);
        List<ReserveMarginCommand> commands = List.of(
                command(UUID.randomUUID(), 5_000),
                command(voidedSubscriptionId, 5_000),
                command(UUID.randomUUID(), 5_000));

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(2, 0, 1));
        assertThat(cashBalance(accountId)).isEqualTo(10_000L);
        assertThat(count("margin")).isEqualTo(2);
        assertThat(count("margin WHERE subscription_id = '" + voidedSubscriptionId + "'")).isZero();
        assertThat(count("processed_event")).isEqualTo(3);
        assertThat(count("outbox")).isEqualTo(2);
    }

    @Test
    @DisplayName("계좌가 없는 건은 거절 응답만 남기고 배치를 롤백하지 않는다")
    void reserveAll_unknownAccount_rejectsWithoutRollingBackBatch() {
        // given
        UUID unknownAccountId = UUID.randomUUID();
        List<ReserveMarginCommand> commands = List.of(
                command(UUID.randomUUID(), 5_000),
                command(unknownAccountId, userId, UUID.randomUUID(), 5_000),
                command(UUID.randomUUID(), 5_000));

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(2, 1, 0));
        assertThat(cashBalance(accountId)).isEqualTo(10_000L);
        assertThat(count("outbox WHERE event_type = 'margin.reserve-failed' AND message_key = '" + unknownAccountId + "'")).isEqualTo(1);
        assertThat(count("processed_event")).isEqualTo(3);
    }

    @Test
    @DisplayName("서로 다른 계좌 30건을 처리하면 INSERT와 UPDATE가 JDBC 배치로 묶여서 준비한 SQL 수가 건수에 비례해 늘지 않는다")
    void reserveAll_manyAccounts_groupsStatementsIntoJdbcBatches() {
        // given: 계좌 30개, 계좌마다 1건
        List<ReserveMarginCommand> commands = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID otherUserId = UUID.randomUUID();
            UUID otherAccountId = UUID.randomUUID();
            insertAccount(otherUserId, otherAccountId, String.format("3%011d", i), 20_000);
            commands.add(command(otherAccountId, otherUserId, UUID.randomUUID(), 1_000));
        }
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        // when
        ReserveBatchResult result = marginReserveService.reserveAll(commands);

        // then
        assertThat(result).isEqualTo(new ReserveBatchResult(30, 0, 0));
        assertThat(statistics.getEntityInsertCount()).isEqualTo(90); // margin, 원장, Outbox 30건씩
        assertThat(statistics.getEntityUpdateCount()).isEqualTo(30); // 계좌 30개
        // 처리 기록 30(건마다) + 조회 3(계좌 잠금, margin, 선행 반환 기록) + 묶인 INSERT 3종류 + 묶인 계좌 UPDATE 1 = 37쯤.
        // 묶지 않으면 30 + 3 + 90 + 30 = 153
        long preparedStatements = statistics.getPrepareStatementCount();
        assertThat(preparedStatements).as("준비한 SQL 수").isLessThanOrEqualTo(commands.size() + 10L);
    }

    @Test
    @DisplayName("같은 두 계좌를 서로 반대 순서로 요청한 배치 둘이 동시에 처리돼도 계좌를 ID 순으로 잠가서 교착 없이 모두 반영된다")
    void reserveAll_overlappingBatchesInOppositeOrder_doNotDeadlock() throws Exception {
        // given: 계좌 둘 (예수금 20,000원씩), 배치 2개가 [A, B], [B, A] 순서로 번갈아 20번 동시에 들어옴
        UUID userA = UUID.randomUUID();
        UUID accountA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        insertAccount(userA, accountA, "400000000001", 20_000);
        insertAccount(userB, accountB, "400000000002", 20_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            for (int round = 0; round < 20; round++) {
                List<ReserveMarginCommand> ab = List.of(
                        command(accountA, userA, UUID.randomUUID(), 100), command(accountB, userB, UUID.randomUUID(), 100));
                List<ReserveMarginCommand> ba = List.of(
                        command(accountB, userB, UUID.randomUUID(), 100), command(accountA, userA, UUID.randomUUID(), 100));
                CountDownLatch start = new CountDownLatch(1);
                Future<?> first = executor.submit(() -> {
                    start.await();
                    return marginReserveService.reserveAll(ab);
                });
                Future<?> second = executor.submit(() -> {
                    start.await();
                    return marginReserveService.reserveAll(ba);
                });

                // when
                start.countDown();

                // then: 하나라도 교착으로 실패하면 get()에서 예외가 난다
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // 계좌마다 20번 x 2건 x 100원
        assertThat(cashBalance(accountA)).isEqualTo(16_000L);
        assertThat(cashBalance(accountB)).isEqualTo(16_000L);
        assertThat(count("margin WHERE account_id IN ('" + accountA + "', '" + accountB + "')")).isEqualTo(80);
    }

    // 이 테스트 클래스의 기본 계좌(accountId)에 대한 요청
    private ReserveMarginCommand command(UUID subscriptionId, long amount) {
        return command(accountId, userId, subscriptionId, amount);
    }

    private ReserveMarginCommand command(UUID targetAccountId, UUID ownerUserId, UUID subscriptionId, long amount) {
        return new ReserveMarginCommand(UUID.randomUUID(), subscriptionId, ownerUserId, targetAccountId, ipoId, amount);
    }

    // 회원 생성 메서드 대신 SQL로 넣는다 (MarginReserveServiceTest와 같은 방식)
    private void insertAccount(UUID ownerUserId, UUID targetAccountId, String accountNumber, long cashBalance) {
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                ownerUserId, "u" + ownerUserId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 0, now(), now(), 'test', 'test')""",
                targetAccountId, ownerUserId, UUID.randomUUID(), accountNumber, cashBalance);
    }

    // 삽입은 반환 Consumer 이슈에서 만들므로 SQL로 넣는다
    private void insertVoidIntent(UUID subscriptionId) {
        jdbcTemplate.update("""
                INSERT INTO margin_void_intent (subscription_id, account_id, reason, created_at, created_by)
                VALUES (?, ?, 'TIMEOUT', now(), 'test')""", subscriptionId, accountId);
    }

    private long cashBalance(UUID targetAccountId) {
        return jdbcTemplate.queryForObject("SELECT cash_balance FROM account WHERE account_id = ?", Long.class, targetAccountId);
    }

    // 표 이름 뒤에 WHERE 조건을 붙여 쓸 수 있다
    private long count(String tableAndCondition) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableAndCondition, Long.class);
    }
}
