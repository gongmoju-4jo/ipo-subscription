package com.gongmoju.account.domain.reconciliation;

import java.util.List;

// 원장 대사 검사 1개의 결과
// totalCount는 전체 불일치 건수(SQL 집계), samples는 그중 조회한 최대 sampleLimit건 (원인 확인용)
// 불일치가 212만 건이어도 메모리에 적재되는 행은 samples 건수로 제한된다
public record LedgerCheckResult(
        LedgerReconciliationCheck check,
        long totalCount,
        List<LedgerMismatch> samples
) {

    public LedgerCheckResult {
        samples = List.copyOf(samples);
    }

    // 조회하지 않은 불일치 건수 (로그의 "외 N건", "N건 생략")
    public long omittedCount() {
        return totalCount - samples.size();
    }
}
