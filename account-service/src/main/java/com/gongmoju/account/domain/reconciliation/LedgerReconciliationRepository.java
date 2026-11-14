package com.gongmoju.account.domain.reconciliation;

// 원장 대사 검사 조회. 조회만 하고 수정하지 않는다. 구현은 infrastructure/persistence (SQL)
public interface LedgerReconciliationRepository {

    // 검사 1개를 실행해서 전체 불일치 건수와 최대 sampleLimit건의 불일치를 반환한다 (정상이면 0건, 빈 목록)
    LedgerCheckResult findMismatches(LedgerReconciliationCheck check, int sampleLimit);
}
