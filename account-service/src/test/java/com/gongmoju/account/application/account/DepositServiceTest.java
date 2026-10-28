package com.gongmoju.account.application.account;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 PostgreSQL(Testcontainers)에서 입금의 락, 멱등, 본인 확인을 확인한다. Docker가 필요하다
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
class DepositServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private DepositService depositService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userId;
    private UUID accountId;

    // 회원, 계좌 생성 메서드는 F-11, F-12에서 추가되므로 SQL로 넣는다
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                userId, "u" + userId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, 0, 0, now(), now(), 'test', 'test')""",
                accountId, userId, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ledger_entry");
        jdbcTemplate.update("DELETE FROM account");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void 입금하면_예수금과_원장이_같이_남는다() {
        DepositResult result = depositService.deposit(accountId, userId, 1_000, UUID.randomUUID());

        assertThat(result.created()).isTrue();
        assertThat(result.cashBalance()).isEqualTo(1_000);
        assertThat(result.marginBalance()).isZero();
        assertThat(result.createdAt()).isNotNull();
        assertThat(cashBalance()).isEqualTo(1_000);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cash_balance_after FROM ledger_entry WHERE ledger_entry_id = ?", Long.class, result.depositId()))
                .isEqualTo(1_000);
    }

    @Test
    void 같은_requestId로_다시_요청하면_입금하지_않고_처음_결과를_준다() {
        UUID requestId = UUID.randomUUID();
        DepositResult first = depositService.deposit(accountId, userId, 1_000, requestId);

        DepositResult second = depositService.deposit(accountId, userId, 1_000, requestId);

        assertThat(second.created()).isFalse();
        assertThat(second).isEqualTo(new DepositResult(first.depositId(), first.accountId(), first.amount(),
                first.cashBalance(), first.marginBalance(), second.createdAt(), false));
        assertThat(cashBalance()).isEqualTo(1_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 같은_requestId로_금액이_다르면_409이고_잔고는_그대로다() {
        UUID requestId = UUID.randomUUID();
        depositService.deposit(accountId, userId, 1_000, requestId);

        assertThatThrownBy(() -> depositService.deposit(accountId, userId, 2_000, requestId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
        assertThat(cashBalance()).isEqualTo(1_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 본인_계좌가_아니면_404이고_입금하지_않는다() {
        assertThatThrownBy(() -> depositService.deposit(accountId, UUID.randomUUID(), 1_000, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
        assertThat(cashBalance()).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void 같은_계좌에_동시에_입금해도_금액이_사라지지_않는다() throws Exception {
        int threads = 50;

        List<DepositResult> results = runConcurrently(threads, i -> () ->
                depositService.deposit(accountId, userId, 1_000, UUID.randomUUID()));

        assertThat(results).hasSize(threads);
        assertThat(cashBalance()).isEqualTo(threads * 1_000L);
        // 락으로 한 줄씩 처리됐다면 거래 후 잔고가 1,000부터 50,000까지 하나씩 겹치지 않고 쌓인다
        assertThat(jdbcTemplate.queryForList(
                "SELECT cash_balance_after FROM ledger_entry ORDER BY cash_balance_after", Long.class))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, threads).map(n -> n * 1_000).boxed().toList());
    }

    @Test
    void 같은_requestId로_동시에_요청해도_한_번만_입금된다() throws Exception {
        UUID requestId = UUID.randomUUID();

        List<DepositResult> results = runConcurrently(10, i -> () ->
                depositService.deposit(accountId, userId, 1_000, requestId));

        assertThat(results).filteredOn(DepositResult::created).hasSize(1);
        assertThat(results).extracting(DepositResult::depositId).containsOnly(results.get(0).depositId());
        assertThat(cashBalance()).isEqualTo(1_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    // threads개를 동시에 출발시키고 결과를 모은다. 하나라도 예외면 get()에서 테스트가 실패한다
    private List<DepositResult> runConcurrently(int threads, java.util.function.IntFunction<Callable<DepositResult>> task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DepositResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<DepositResult> call = task.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<DepositResult> results = new ArrayList<>();
            for (Future<DepositResult> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private long cashBalance() {
        return jdbcTemplate.queryForObject("SELECT cash_balance FROM account WHERE account_id = ?", Long.class, accountId);
    }

    private long ledgerCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
    }
}
