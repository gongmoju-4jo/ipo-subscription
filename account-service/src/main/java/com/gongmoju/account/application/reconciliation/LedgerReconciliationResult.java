package com.gongmoju.account.application.reconciliation;

import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;

import java.util.List;

// 원장 대사 1회 실행 결과. 모든 검사의 불일치를 포함한다 (정상이면 빈 목록)
public record LedgerReconciliationResult(List<LedgerMismatch> mismatches) {

    public LedgerReconciliationResult {
        mismatches = List.copyOf(mismatches);
    }

    public boolean consistent() {
        return mismatches.isEmpty();
    }

    // 검사 1개의 불일치 목록 (검사별 ERROR 로그용)
    public List<LedgerMismatch> mismatchesOf(LedgerReconciliationCheck check) {
        return mismatches.stream().filter(m -> m.check() == check).toList();
    }

    // 검사별 불일치 건수. 0건인 검사도 로그에 기록하려고 제공한다
    public long count(LedgerReconciliationCheck check) {
        return mismatchesOf(check).size();
    }
}
