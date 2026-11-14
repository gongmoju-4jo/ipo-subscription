package com.gongmoju.account.application.reconciliation;

import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_MARGIN_ACCOUNT;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_SUM;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_BALANCE;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_PENDING_LEDGER;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_STATUS_LEDGER;
import static org.assertj.core.api.Assertions.assertThat;

// 원장 대사 실행 시간 측정 (#132). 환경변수 LEDGER_MEASURE_ACCOUNTS가 있을 때만 실행한다 (CI, 평소 테스트에서는 실행하지 않음)
// 실행: LEDGER_MEASURE_ACCOUNTS=10000 ./gradlew :account-service:test --tests '*LedgerReconciliationMeasureTest' -i
// DB 자원 상한은 배포 환경(compose.perf.yml의 postgres)과 동일하게 CPU 2.0, 메모리 1g. 결과는 "[측정]"으로 시작하는 줄로 출력한다
@Testcontainers
@EnabledIfEnvironmentVariable(named = "LEDGER_MEASURE_ACCOUNTS", matches = "\\d+")
@SpringBootTest(properties = "eureka.client.enabled=false")
class LedgerReconciliationMeasureTest {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURE_RUNS = 5;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                    .withNanoCPUs(2_000_000_000L)
                    .withMemory(1024L * 1024 * 1024));

    // 계좌마다 입금 1건, 증거금 3건(PENDING, POSTED, VOIDED), 원장 7줄. 모든 검사가 조회할 데이터가 있게 구성한다
    // 금액: 입금 1,000,000 / PENDING 30,000 / POSTED 20,000 (배정 대금 5,000, 환불 15,000) / VOIDED 10,000
    // 결과 잔고: 예수금 965,000, 청약증거금 30,000
    private static final String SEED_SQL = """
            CREATE TEMP TABLE seed ON COMMIT DROP AS
            SELECT n, gen_random_uuid() AS user_id, gen_random_uuid() AS account_id,
                   gen_random_uuid() AS pending_margin_id, gen_random_uuid() AS pending_subscription_id,
                   gen_random_uuid() AS posted_margin_id, gen_random_uuid() AS posted_subscription_id,
                   gen_random_uuid() AS voided_margin_id, gen_random_uuid() AS voided_subscription_id
            FROM generate_series(1, ?) AS n;

            INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
            SELECT user_id, 'm' || n, 'hash', '측정', 'USER', now(), now(), 'measure', 'measure' FROM seed;

            INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance,
                                 created_at, updated_at, created_by, updated_by)
            SELECT account_id, user_id, gen_random_uuid(), lpad(n::text, 12, '0'), 965000, 30000,
                   now(), now(), 'measure', 'measure' FROM seed;

            INSERT INTO margin (margin_id, account_id, subscription_id, ipo_id, amount, posted_amount, status, settled_at,
                                created_at, updated_at, created_by, updated_by)
            SELECT v.margin_id, s.account_id, v.subscription_id, ?::uuid, v.amount, v.posted_amount, v.status, v.settled_at,
                   now(), now(), 'measure', 'measure'
            FROM seed s CROSS JOIN LATERAL (VALUES
                (s.pending_margin_id, s.pending_subscription_id, 30000, NULL::bigint, 'PENDING', NULL::timestamptz),
                (s.posted_margin_id, s.posted_subscription_id, 20000, 5000, 'POSTED', now()),
                (s.voided_margin_id, s.voided_subscription_id, 10000, NULL, 'VOIDED', now())
            ) AS v(margin_id, subscription_id, amount, posted_amount, status, settled_at);

            INSERT INTO ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after,
                                      margin_id, subscription_id, request_id, created_at, created_by)
            SELECT gen_random_uuid(), s.account_id, v.type, v.amount, v.cash_after, v.margin_after,
                   v.margin_id, v.subscription_id, v.request_id, clock_timestamp(), 'measure'
            FROM seed s CROSS JOIN LATERAL (VALUES
                ('DEPOSIT', 1000000, 1000000, 0, NULL::uuid, NULL::uuid, gen_random_uuid()),
                ('MARGIN_PENDING', 30000, 970000, 30000, s.pending_margin_id, s.pending_subscription_id, NULL),
                ('MARGIN_PENDING', 20000, 950000, 50000, s.posted_margin_id, s.posted_subscription_id, NULL),
                ('MARGIN_PENDING', 10000, 940000, 60000, s.voided_margin_id, s.voided_subscription_id, NULL),
                ('MARGIN_POST', 5000, 940000, 55000, s.posted_margin_id, s.posted_subscription_id, NULL),
                ('REFUND', 15000, 955000, 40000, s.posted_margin_id, s.posted_subscription_id, NULL),
                ('MARGIN_VOID', 10000, 965000, 30000, s.voided_margin_id, s.voided_subscription_id, NULL)
            ) AS v(type, amount, cash_after, margin_after, margin_id, subscription_id, request_id);
            """;

    @Autowired
    private LedgerReconciliationService reconciliationService;

    @Autowired
    private LedgerReconciliationRepository reconciliationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void 계좌_수별_대사_실행_시간을_측정하고_주입한_불일치를_검출한다() {
        int accounts = Integer.parseInt(System.getenv("LEDGER_MEASURE_ACCOUNTS"));
        long seedMillis = elapsedMillis(() -> {
            seed(accounts);
            return null;
        });
        jdbcTemplate.execute("ANALYZE");
        printDataSize(accounts, seedMillis);

        // 정상 데이터에서 불일치 0건 (시드 자체 검증)
        assertThat(reconciliationService.reconcile().consistent()).isTrue();

        List<Long> totalMillis = measure(() -> reconciliationService.reconcile());
        System.out.println("[측정] 전체 대사 실행 시간(ms) 5회=" + totalMillis + ", 중앙값=" + median(totalMillis));
        for (LedgerReconciliationCheck check : LedgerReconciliationCheck.values()) {
            List<Long> checkMillis = measure(() -> reconciliationRepository.findMismatches(check, LedgerReconciliationService.SAMPLE_LIMIT));
            System.out.println("[측정] 검사 " + check + " 실행 시간(ms) 5회=" + checkMillis + ", 중앙값=" + median(checkMillis));
        }

        injectMismatches();
        LedgerReconciliationResult result = reconciliationService.reconcile();
        System.out.println("[측정] 불일치 주입 후 검출: 전체 " + result.totalCount() + "건, "
                + Arrays.stream(LedgerReconciliationCheck.values()).map(c -> c + "=" + result.count(c)).toList());
        assertThat(result.count(LEDGER_SUM)).isEqualTo(2);
        assertThat(result.count(MARGIN_PENDING_LEDGER)).isEqualTo(1);
        assertThat(result.count(MARGIN_BALANCE)).isEqualTo(1);
        assertThat(result.count(MARGIN_STATUS_LEDGER)).isEqualTo(1);
        assertThat(result.count(LEDGER_MARGIN_ACCOUNT)).isEqualTo(2);

        // 전체 계좌 불일치 (예: 잔고 계산 버그 배포): 건수는 전부 집계하고, 메모리에 적재하는 행은 조회 상한까지만
        jdbcTemplate.update("UPDATE account SET cash_balance = cash_balance - 1");
        long allMismatchStart = System.nanoTime();
        LedgerReconciliationResult allMismatch = reconciliationService.reconcile();
        long allMismatchMillis = (System.nanoTime() - allMismatchStart) / 1_000_000;
        System.out.println("[측정] 전체 계좌 불일치: LEDGER_SUM 전체 " + allMismatch.count(LEDGER_SUM) + "건, 조회 "
                + allMismatch.mismatchesOf(LEDGER_SUM).size() + "건, 실행 시간 " + allMismatchMillis + "ms");
        assertThat(allMismatch.count(LEDGER_SUM)).isEqualTo(accounts);
        assertThat(allMismatch.mismatchesOf(LEDGER_SUM)).hasSize(LedgerReconciliationService.SAMPLE_LIMIT);
    }

    // 시드는 임시 테이블을 사용해서 트랜잭션 1개(커넥션 1개)에서 실행한다
    private void seed(int accounts) {
        transactionTemplate.executeWithoutResult(status -> {
            String[] statements = SEED_SQL.split(";\\s*\\n");
            jdbcTemplate.update(statements[0], accounts);
            jdbcTemplate.update(statements[1]);
            jdbcTemplate.update(statements[2]);
            jdbcTemplate.update(statements[3], UUID.randomUUID().toString());
            jdbcTemplate.update(statements[4]);
        });
    }

    // 계좌 5개에 불일치 4종류를 주입한다
    // 기대 검출: LEDGER_SUM 2, MARGIN_PENDING_LEDGER 1, MARGIN_BALANCE 1, MARGIN_STATUS_LEDGER 1, LEDGER_MARGIN_ACCOUNT 2
    private void injectMismatches() {
        // 계좌 1: 원장 없이 예수금 1,000 차감 → LEDGER_SUM
        jdbcTemplate.update("""
                UPDATE account SET cash_balance = cash_balance - 1000
                WHERE account_id = (SELECT account_id FROM account WHERE account_number = lpad('1', 12, '0'))""");
        // 계좌 2: PENDING 증거금의 인출 원장 중복 → MARGIN_PENDING_LEDGER, LEDGER_SUM
        jdbcTemplate.update("""
                INSERT INTO ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after,
                                          margin_id, subscription_id, created_at, created_by)
                SELECT gen_random_uuid(), m.account_id, 'MARGIN_PENDING', m.amount, 0, 0, m.margin_id, m.subscription_id, now(), 'measure'
                FROM margin m JOIN account a ON a.account_id = m.account_id
                WHERE a.account_number = lpad('2', 12, '0') AND m.status = 'PENDING'""");
        // 계좌 3: 원장 없이 PENDING 증거금을 VOIDED로 변경 → MARGIN_BALANCE, MARGIN_STATUS_LEDGER
        jdbcTemplate.update("""
                UPDATE margin SET status = 'VOIDED'
                WHERE status = 'PENDING' AND account_id = (SELECT account_id FROM account WHERE account_number = lpad('3', 12, '0'))""");
        // 계좌 4, 5: 같은 금액(30,000)인 PENDING 증거금의 인출 원장 연결을 서로 교체 → LEDGER_MARGIN_ACCOUNT 2 (원장 2줄)
        jdbcTemplate.update("""
                WITH p AS (
                    SELECT m.margin_id, a.account_number
                    FROM margin m JOIN account a ON a.account_id = m.account_id
                    WHERE m.status = 'PENDING' AND a.account_number IN (lpad('4', 12, '0'), lpad('5', 12, '0'))
                )
                UPDATE ledger_entry l
                SET margin_id = (SELECT p2.margin_id FROM p p1 JOIN p p2 ON p1.account_number <> p2.account_number
                                 WHERE p1.margin_id = l.margin_id)
                WHERE l.type = 'MARGIN_PENDING' AND l.margin_id IN (SELECT margin_id FROM p)""");
    }

    private void printDataSize(int accounts, long seedMillis) {
        System.out.println("[측정] 계좌 " + accounts + "건, 시드 생성 " + seedMillis + "ms, 행 수: "
                + "account=" + count("account") + ", margin=" + count("margin") + ", ledger_entry=" + count("ledger_entry")
                + ", DB 크기=" + jdbcTemplate.queryForObject("SELECT pg_size_pretty(pg_database_size(current_database()))", String.class));
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private List<Long> measure(Supplier<?> task) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            task.get();
        }
        List<Long> results = new ArrayList<>();
        for (int i = 0; i < MEASURE_RUNS; i++) {
            results.add(elapsedMillis(task));
        }
        return results;
    }

    private long elapsedMillis(Supplier<?> task) {
        long start = System.nanoTime();
        task.get();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private long median(List<Long> values) {
        return values.stream().sorted().toList().get(values.size() / 2);
    }
}
