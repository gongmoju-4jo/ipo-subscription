package com.gongmoju.account.domain.reconciliation;

import java.util.List;

// 원장 대사 검사 조회. 조회만 하고 수정하지 않는다. 구현은 infrastructure/persistence (SQL)
public interface LedgerReconciliationRepository {

    // 검사 1개를 실행해서 불일치만 반환한다 (정상이면 빈 목록)
    List<LedgerMismatch> findMismatches(LedgerReconciliationCheck check);
}
