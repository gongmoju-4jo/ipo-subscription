package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_SUM;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_BALANCE;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_PENDING_LEDGER;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_STATUS_LEDGER;

// 원장 대사 검사 SQL (PostgreSQL). loadtest/sql/verify.sql의 계좌 단독 검사를 이동했고, 공모주 조건 없이 전체 계좌, 증거금을 검사한다
// 집계 조회라 JPA 대신 JdbcTemplate을 사용한다. 호출한 트랜잭션의 커넥션을 동일하게 사용한다
// 검사마다 불일치 행만 조회하고, detail에 원인 확인용 기대값과 실제값을 기록한다
@Repository
@RequiredArgsConstructor
class LedgerReconciliationJdbcRepository implements LedgerReconciliationRepository {

    // (2번) 계좌 청약증거금 = PENDING 증거금 금액 합계
    private static final String MARGIN_BALANCE_SQL = """
            SELECT a.account_id, a.margin_balance, COALESCE(SUM(m.amount), 0) AS pending_sum
            FROM account a
            LEFT JOIN margin m ON m.account_id = a.account_id AND m.status = 'PENDING'
            GROUP BY a.account_id, a.margin_balance
            HAVING a.margin_balance <> COALESCE(SUM(m.amount), 0)
            """;

    // (3번을 합계 방식으로 변경) 원장 금액을 유형별 부호로 더한 값 = 계좌 잔고
    // 예수금: 입금 +, 인출 -, 반환 +, 환불 + / 청약증거금: 인출 +, 정산 -, 반환 -, 환불 -
    // 원장이 없는 계좌도 합계 0으로 비교한다 (원장 없이 잔고만 있으면 불일치)
    private static final String LEDGER_SUM_SQL = """
            WITH l AS (
                SELECT account_id,
                       SUM(CASE type WHEN 'DEPOSIT' THEN amount
                                     WHEN 'MARGIN_PENDING' THEN -amount
                                     WHEN 'MARGIN_VOID' THEN amount
                                     WHEN 'REFUND' THEN amount
                                     ELSE 0 END) AS cash,
                       SUM(CASE type WHEN 'MARGIN_PENDING' THEN amount
                                     WHEN 'MARGIN_POST' THEN -amount
                                     WHEN 'MARGIN_VOID' THEN -amount
                                     WHEN 'REFUND' THEN -amount
                                     ELSE 0 END) AS margin
                FROM ledger_entry
                GROUP BY account_id
            )
            SELECT a.account_id, a.cash_balance, a.margin_balance,
                   COALESCE(l.cash, 0) AS ledger_cash, COALESCE(l.margin, 0) AS ledger_margin
            FROM account a
            LEFT JOIN l ON l.account_id = a.account_id
            WHERE a.cash_balance <> COALESCE(l.cash, 0)
               OR a.margin_balance <> COALESCE(l.margin, 0)
            """;

    // (11번) 증거금마다 인출 원장이 정확히 1줄, 금액 = 증거금. 중복 인출, 원장 누락을 검출한다
    private static final String MARGIN_PENDING_LEDGER_SQL = """
            SELECT m.account_id, m.margin_id, m.amount,
                   count(l.ledger_entry_id) AS ledger_count, COALESCE(SUM(l.amount), 0) AS ledger_amount
            FROM margin m
            LEFT JOIN ledger_entry l ON l.margin_id = m.margin_id AND l.type = 'MARGIN_PENDING'
            GROUP BY m.margin_id, m.account_id, m.amount
            HAVING count(l.ledger_entry_id) <> 1
                OR COALESCE(SUM(l.amount), 0) <> m.amount
            """;

    // (12번) 증거금 상태별 원장 금액
    // PENDING: 정산, 환불, 반환 원장 없음
    // POSTED: 정산 = posted_amount, 환불 = 증거금 - posted_amount, 반환 없음
    // VOIDED: 반환 = 증거금, 정산, 환불 없음
    private static final String MARGIN_STATUS_LEDGER_SQL = """
            WITH l AS (
                SELECT margin_id,
                       COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_POST'), 0) AS post,
                       COALESCE(SUM(amount) FILTER (WHERE type = 'REFUND'), 0) AS refund,
                       COALESCE(SUM(amount) FILTER (WHERE type = 'MARGIN_VOID'), 0) AS void_amount
                FROM ledger_entry
                WHERE margin_id IS NOT NULL
                GROUP BY margin_id
            )
            SELECT m.account_id, m.margin_id, m.status, m.amount, m.posted_amount,
                   COALESCE(l.post, 0) AS post, COALESCE(l.refund, 0) AS refund, COALESCE(l.void_amount, 0) AS void_amount
            FROM margin m
            LEFT JOIN l ON l.margin_id = m.margin_id
            WHERE NOT COALESCE(CASE m.status
                WHEN 'PENDING' THEN COALESCE(l.post, 0) + COALESCE(l.refund, 0) + COALESCE(l.void_amount, 0) = 0
                WHEN 'POSTED' THEN COALESCE(l.post, 0) = m.posted_amount
                               AND COALESCE(l.refund, 0) = m.amount - m.posted_amount
                               AND COALESCE(l.void_amount, 0) = 0
                WHEN 'VOIDED' THEN COALESCE(l.void_amount, 0) = m.amount
                               AND COALESCE(l.post, 0) + COALESCE(l.refund, 0) = 0
                ELSE false END, false)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<LedgerMismatch> findMismatches(LedgerReconciliationCheck check) {
        return switch (check) {
            case MARGIN_BALANCE -> findMarginBalanceMismatches();
            case LEDGER_SUM -> findLedgerSumMismatches();
            case MARGIN_PENDING_LEDGER -> findMarginPendingLedgerMismatches();
            case MARGIN_STATUS_LEDGER -> findMarginStatusLedgerMismatches();
        };
    }

    private List<LedgerMismatch> findMarginBalanceMismatches() {
        return jdbcTemplate.query(MARGIN_BALANCE_SQL, (rs, rowNum) -> new LedgerMismatch(
                MARGIN_BALANCE,
                rs.getObject("account_id", UUID.class),
                null,
                "청약증거금=" + rs.getLong("margin_balance")
                        + ", PENDING 증거금 합계=" + rs.getLong("pending_sum")));
    }

    private List<LedgerMismatch> findLedgerSumMismatches() {
        return jdbcTemplate.query(LEDGER_SUM_SQL, (rs, rowNum) -> new LedgerMismatch(
                LEDGER_SUM,
                rs.getObject("account_id", UUID.class),
                null,
                "예수금=" + rs.getLong("cash_balance")
                        + ", 원장 예수금 합계=" + rs.getLong("ledger_cash")
                        + ", 청약증거금=" + rs.getLong("margin_balance")
                        + ", 원장 청약증거금 합계=" + rs.getLong("ledger_margin")));
    }

    private List<LedgerMismatch> findMarginPendingLedgerMismatches() {
        return jdbcTemplate.query(MARGIN_PENDING_LEDGER_SQL, (rs, rowNum) -> new LedgerMismatch(
                MARGIN_PENDING_LEDGER,
                rs.getObject("account_id", UUID.class),
                rs.getObject("margin_id", UUID.class),
                "증거금=" + rs.getLong("amount")
                        + ", 인출 원장 " + rs.getLong("ledger_count") + "줄"
                        + ", 인출 원장 합계=" + rs.getLong("ledger_amount")));
    }

    private List<LedgerMismatch> findMarginStatusLedgerMismatches() {
        return jdbcTemplate.query(MARGIN_STATUS_LEDGER_SQL, (rs, rowNum) -> new LedgerMismatch(
                MARGIN_STATUS_LEDGER,
                rs.getObject("account_id", UUID.class),
                rs.getObject("margin_id", UUID.class),
                "상태=" + rs.getString("status")
                        + ", 증거금=" + rs.getLong("amount")
                        + ", posted_amount=" + rs.getObject("posted_amount")
                        + ", 정산 원장=" + rs.getLong("post")
                        + ", 환불 원장=" + rs.getLong("refund")
                        + ", 반환 원장=" + rs.getLong("void_amount")));
    }
}
