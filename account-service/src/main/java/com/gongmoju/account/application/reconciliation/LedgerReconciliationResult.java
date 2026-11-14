package com.gongmoju.account.application.reconciliation;

import com.gongmoju.account.domain.reconciliation.LedgerCheckResult;
import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;

import java.util.List;

// 원장 대사 1회 실행 결과. 검사별 전체 불일치 건수와 조회한 불일치(검사당 최대 SAMPLE_LIMIT건)를 포함한다
public record LedgerReconciliationResult(List<LedgerCheckResult> checkResults) {

    public LedgerReconciliationResult {
        checkResults = List.copyOf(checkResults);
    }

    public boolean consistent() {
        return totalCount() == 0;
    }

    // 모든 검사의 전체 불일치 건수 합계
    public long totalCount() {
        return checkResults.stream().mapToLong(LedgerCheckResult::totalCount).sum();
    }

    // 검사별 전체 불일치 건수. 0건인 검사도 로그에 기록하려고 제공한다
    public long count(LedgerReconciliationCheck check) {
        return checkResult(check).totalCount();
    }

    // 검사 1개의 조회한 불일치 (최대 SAMPLE_LIMIT건)
    public List<LedgerMismatch> mismatchesOf(LedgerReconciliationCheck check) {
        return checkResult(check).samples();
    }

    // 모든 검사의 조회한 불일치
    public List<LedgerMismatch> mismatches() {
        return checkResults.stream().flatMap(result -> result.samples().stream()).toList();
    }

    // 실행하지 않은 검사는 불일치 0건으로 취급한다
    public LedgerCheckResult checkResult(LedgerReconciliationCheck check) {
        return checkResults.stream()
                .filter(result -> result.check() == check)
                .findFirst()
                .orElse(new LedgerCheckResult(check, 0, List.of()));
    }
}
