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

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 PostgreSQL(Testcontainers)에서 증거금 인출의 정상 경로(1단계), 거절과 선행 반환 기록(2단계), 중복 처리(3단계)를 확인한다. Docker가 필요하다
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
class MarginReserveServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private MarginReserveService marginReserveService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;
    private UUID ipoId;

    // 회원 생성 메서드 대신 SQL로 넣는다 (DepositServiceTest와 같은 방식). 예수금 20,000원으로 시작
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ipoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                userId, "u" + userId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, '100000000001', 20000, 0, now(), now(), 'test', 'test')""",
                accountId, userId, UUID.randomUUID());
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
    void 인출하면_예수금이_청약증거금으로_옮겨지고_합계는_그대로다() {
        marginReserveService.reserve(command(UUID.randomUUID(), 15_000));

        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account.get("cash_balance")).isEqualTo(5_000L);
        assertThat(account.get("margin_balance")).isEqualTo(15_000L);
    }

    @Test
    void 인출하면_PENDING_margin과_거래_후_잔고가_맞는_원장이_남는다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));

        Map<String, Object> margin = jdbcTemplate.queryForMap(
                "SELECT margin_id, account_id, amount, status FROM margin WHERE subscription_id = ?", subscriptionId);
        assertThat(margin.get("status")).isEqualTo("PENDING");
        assertThat(margin.get("amount")).isEqualTo(15_000L);
        assertThat(margin.get("account_id")).isEqualTo(accountId);

        Map<String, Object> ledger = jdbcTemplate.queryForMap(
                "SELECT type, amount, cash_balance_after, margin_balance_after, margin_id, subscription_id FROM ledger_entry");
        assertThat(ledger.get("type")).isEqualTo("MARGIN_PENDING");
        assertThat(ledger.get("amount")).isEqualTo(15_000L);
        assertThat(ledger.get("cash_balance_after")).isEqualTo(5_000L);
        assertThat(ledger.get("margin_balance_after")).isEqualTo(15_000L);
        assertThat(ledger.get("margin_id")).isEqualTo(margin.get("margin_id"));
        assertThat(ledger.get("subscription_id")).isEqualTo(subscriptionId);
    }

    @Test
    void 인출하면_margin_reserved가_Outbox에_계좌_키로_저장된다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));

        UUID marginId = jdbcTemplate.queryForObject(
                "SELECT margin_id FROM margin WHERE subscription_id = ?", UUID.class, subscriptionId);
        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT aggregate_type, aggregate_id, event_type, topic, message_key, published_at,
                       payload->>'subscriptionId' AS subscription_id, payload->>'marginId' AS margin_id,
                       (payload->>'amount')::bigint AS amount
                FROM outbox""");
        assertThat(outbox.get("aggregate_type")).isEqualTo("MARGIN");
        assertThat(outbox.get("aggregate_id")).isEqualTo(marginId);
        assertThat(outbox.get("event_type")).isEqualTo("margin.reserved");
        assertThat(outbox.get("topic")).isEqualTo("margin.reserved");
        assertThat(outbox.get("message_key")).isEqualTo(accountId.toString());
        assertThat(outbox.get("published_at")).isNull();
        assertThat(outbox.get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(outbox.get("margin_id")).isEqualTo(marginId.toString());
        assertThat(outbox.get("amount")).isEqualTo(15_000L);
    }

    @Test
    void reservedAt은_margin의_created_at과_같은_시각이다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));

        Timestamp createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM margin WHERE subscription_id = ?", Timestamp.class, subscriptionId);
        String reservedAt = jdbcTemplate.queryForObject("SELECT payload->>'reservedAt' FROM outbox", String.class);

        assertThat(reservedAt).isNotNull();
        // 이벤트 명세 시각 형식 (+09:00, 밀리초)으로 같은 값인지 본다
        assertThat(reservedAt).isEqualTo(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
                .format(OffsetDateTime.ofInstant(createdAt.toInstant(), ZoneOffset.ofHours(9))));
    }

    @Test
    void 처리한_이벤트가_같은_트랜잭션에_기록된다() {
        UUID eventId = UUID.randomUUID();
        marginReserveService.reserve(new ReserveMarginCommand(eventId, UUID.randomUUID(), userId, accountId, UUID.randomUUID(), 15_000));

        Map<String, Object> processed = jdbcTemplate.queryForMap("SELECT event_id, consumer_name, topic FROM processed_event");
        assertThat(processed.get("event_id")).isEqualTo(eventId);
        assertThat(processed.get("consumer_name")).isEqualTo("account-margin-reserve");
        assertThat(processed.get("topic")).isEqualTo("margin.reserve-requested");
    }

    @Test
    void 같은_계좌에_다른_청약이_동시에_와도_잔고와_원장이_어긋나지_않는다() throws Exception {
        int threads = 20; // 1,000원씩 20건 = 20,000원 (예수금 전부)

        runConcurrently(threads, () -> marginReserveService.reserve(command(UUID.randomUUID(), 1_000)));

        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account.get("cash_balance")).isEqualTo(0L);
        assertThat(account.get("margin_balance")).isEqualTo(20_000L);
        // 락으로 한 줄씩 처리됐다면 거래 후 청약증거금이 1,000부터 20,000까지 겹치지 않고 쌓인다
        assertThat(jdbcTemplate.queryForList(
                "SELECT margin_balance_after FROM ledger_entry ORDER BY margin_balance_after", Long.class))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, threads).map(n -> n * 1_000).boxed().toList());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Long.class)).isEqualTo(threads);
    }

    @Test
    void 계좌가_없으면_ACCOUNT_NOT_FOUND_거절을_커밋한다() {
        UUID subscriptionId = UUID.randomUUID();
        UUID unknownAccountId = UUID.randomUUID();

        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, unknownAccountId, UUID.randomUUID(), 15_000));

        assertRejected(subscriptionId, unknownAccountId, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void 계좌_소유자가_다르면_ACCOUNT_NOT_FOUND_거절을_커밋한다() {
        UUID subscriptionId = UUID.randomUUID();

        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, UUID.randomUUID(), accountId, UUID.randomUUID(), 15_000));

        assertRejected(subscriptionId, accountId, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void 예수금이_부족하면_INSUFFICIENT_BALANCE_거절을_커밋한다() {
        UUID subscriptionId = UUID.randomUUID();

        marginReserveService.reserve(command(subscriptionId, 20_001)); // 예수금 20,000원

        assertRejected(subscriptionId, accountId, "INSUFFICIENT_BALANCE");
    }

    @Test
    void 선행_반환_기록이_있으면_돈을_옮기지_않고_응답도_보내지_않는다() {
        UUID subscriptionId = UUID.randomUUID();
        insertVoidIntent(subscriptionId);

        marginReserveService.reserve(command(subscriptionId, 15_000));

        assertNothingMoved();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event", Long.class)).isEqualTo(1L);
    }

    @Test
    void 선행_반환_기록이_잔액_부족보다_먼저다() {
        UUID subscriptionId = UUID.randomUUID();
        insertVoidIntent(subscriptionId);

        marginReserveService.reserve(command(subscriptionId, 20_001));

        // 이미 취소된 청약에 reserve-failed가 나가면 안 된다
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event", Long.class)).isEqualTo(1L);
    }

    @Test
    void 같은_eventId를_다시_받으면_아무것도_하지_않는다() {
        ReserveMarginCommand command = command(UUID.randomUUID(), 15_000);

        marginReserveService.reserve(command);
        marginReserveService.reserve(command);

        assertReservedOnce(15_000);
        assertThat(count("outbox")).isEqualTo(1L);
        assertThat(count("processed_event")).isEqualTo(1L);
    }

    @Test
    void 같은_eventId가_동시에_와도_한_번만_처리된다() throws Exception {
        ReserveMarginCommand command = command(UUID.randomUUID(), 1_000);

        runConcurrently(10, () -> marginReserveService.reserve(command));

        assertReservedOnce(1_000);
        assertThat(count("outbox")).isEqualTo(1L);
        assertThat(count("processed_event")).isEqualTo(1L);
    }

    @Test
    void PENDING인_청약을_다시_요청하면_돈은_그대로고_같은_margin_reserved를_재발행한다() {
        UUID subscriptionId = UUID.randomUUID();

        marginReserveService.reserve(command(subscriptionId, 15_000));
        marginReserveService.reserve(command(subscriptionId, 15_000)); // 새 eventId

        assertReservedOnce(15_000);
        List<Map<String, Object>> outbox = jdbcTemplate.queryForList("""
                SELECT event_type, aggregate_id, payload->>'marginId' AS margin_id, payload->>'reservedAt' AS reserved_at
                FROM outbox ORDER BY created_at""");
        assertThat(outbox).hasSize(2);
        assertThat(outbox).allSatisfy(row -> assertThat(row.get("event_type")).isEqualTo("margin.reserved"));
        // 재발행은 최초 인출의 marginId, reservedAt을 그대로 쓴다
        assertThat(outbox.get(1).get("margin_id")).isEqualTo(outbox.get(0).get("margin_id"));
        assertThat(outbox.get(1).get("reserved_at")).isEqualTo(outbox.get(0).get("reserved_at"));
        assertThat(count("processed_event")).isEqualTo(2L);
    }

    @Test
    void 이미_반환된_청약을_다시_요청하면_돈도_응답도_없다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));
        // 반환 처리는 다른 이슈라 상태만 SQL로 바꾼다
        jdbcTemplate.update("UPDATE margin SET status = 'VOIDED' WHERE subscription_id = ?", subscriptionId);

        marginReserveService.reserve(command(subscriptionId, 15_000));

        assertReservedOnce(15_000);
        assertThat(count("outbox")).isEqualTo(1L); // 처음 인출의 응답만
        assertThat(count("processed_event")).isEqualTo(2L);
    }

    @Test
    void 이미_정산된_청약을_다시_요청하면_돈도_응답도_없다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));
        jdbcTemplate.update("UPDATE margin SET status = 'POSTED', posted_amount = 10000 WHERE subscription_id = ?", subscriptionId);

        marginReserveService.reserve(command(subscriptionId, 15_000));

        assertReservedOnce(15_000);
        assertThat(count("outbox")).isEqualTo(1L);
        assertThat(count("processed_event")).isEqualTo(2L);
    }

    @Test
    void 같은_청약인데_금액이_다르면_예외로_롤백한다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));

        assertThatThrownBy(() -> marginReserveService.reserve(command(subscriptionId, 14_000)))
                .isInstanceOf(InvalidEventException.class);

        assertMismatchRolledBack();
    }

    @Test
    void 같은_청약인데_종목이_다르면_예외로_롤백한다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));

        assertThatThrownBy(() -> marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, UUID.randomUUID(), 15_000)))
                .isInstanceOf(InvalidEventException.class);

        assertMismatchRolledBack();
    }

    @Test
    void 같은_청약인데_계좌가_다르면_예외로_롤백한다() {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(command(subscriptionId, 15_000));
        // 같은 회원의 다른 계좌 (소유권 검사는 통과해야 기존 margin 비교까지 간다)
        UUID otherAccountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, '100000000002', 20000, 0, now(), now(), 'test', 'test')""",
                otherAccountId, userId, UUID.randomUUID());

        assertThatThrownBy(() -> marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, otherAccountId, ipoId, 15_000)))
                .isInstanceOf(InvalidEventException.class);

        assertMismatchRolledBack();
    }

    // 불일치: 첫 인출만 남고 두 번째 요청은 처리 기록까지 롤백된다 (재시도 후 DLT 대상)
    private void assertMismatchRolledBack() {
        assertReservedOnce(15_000);
        assertThat(count("outbox")).isEqualTo(1L);
        assertThat(count("processed_event")).isEqualTo(1L);
    }

    // 돈이 한 번만 옮겨졌는지: 잔고, margin, 원장이 모두 1건분
    private void assertReservedOnce(long amount) {
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account.get("cash_balance")).isEqualTo(20_000L - amount);
        assertThat(account.get("margin_balance")).isEqualTo(amount);
        assertThat(count("margin")).isEqualTo(1L);
        assertThat(count("ledger_entry")).isEqualTo(1L);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    // 거절: 예외 없이 커밋되고, 돈은 그대로, reserve-failed 1건과 처리 기록 1건이 남는다
    private void assertRejected(UUID subscriptionId, UUID messageKeyAccountId, String reason) {
        assertNothingMoved();
        Map<String, Object> outbox = jdbcTemplate.queryForMap("""
                SELECT aggregate_type, aggregate_id, event_type, topic, message_key,
                       payload->>'subscriptionId' AS subscription_id, payload->>'reason' AS reason
                FROM outbox""");
        assertThat(outbox.get("aggregate_type")).isEqualTo("MARGIN");
        assertThat(outbox.get("aggregate_id")).isEqualTo(subscriptionId); // margin이 없어서 청약 ID
        assertThat(outbox.get("event_type")).isEqualTo("margin.reserve-failed");
        assertThat(outbox.get("topic")).isEqualTo("margin.reserve-failed");
        assertThat(outbox.get("message_key")).isEqualTo(messageKeyAccountId.toString());
        assertThat(outbox.get("subscription_id")).isEqualTo(subscriptionId.toString());
        assertThat(outbox.get("reason")).isEqualTo(reason);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event", Long.class)).isEqualTo(1L);
    }

    private void assertNothingMoved() {
        Map<String, Object> account = jdbcTemplate.queryForMap(
                "SELECT cash_balance, margin_balance FROM account WHERE account_id = ?", accountId);
        assertThat(account.get("cash_balance")).isEqualTo(20_000L);
        assertThat(account.get("margin_balance")).isEqualTo(0L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM margin", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry", Long.class)).isZero();
    }

    // 삽입은 반환 Consumer 이슈에서 만들므로 SQL로 넣는다
    private void insertVoidIntent(UUID subscriptionId) {
        jdbcTemplate.update("""
                INSERT INTO margin_void_intent (subscription_id, account_id, reason, created_at, created_by)
                VALUES (?, ?, 'TIMEOUT', now(), 'test')""", subscriptionId, accountId);
    }

    private ReserveMarginCommand command(UUID subscriptionId, long amount) {
        return new ReserveMarginCommand(UUID.randomUUID(), subscriptionId, userId, accountId, ipoId, amount);
    }

    // threads개를 동시에 출발시킨다. 하나라도 예외면 get()에서 테스트가 실패한다
    private void runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    task.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
