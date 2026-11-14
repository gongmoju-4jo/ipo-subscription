package com.gongmoju.account.domain.reconciliation;

import java.util.UUID;

// 원장 대사 검사에서 발견한 불일치 1건
// marginId는 증거금 단위 검사(11, 12번)에서만 할당된다. 계좌 단위 검사(2, 3번)는 null이다
// detail은 원인 확인용 기대값과 실제값이다 (예: "청약증거금=15000, PENDING 증거금 합계=10000")
public record LedgerMismatch(
        LedgerReconciliationCheck check,
        UUID accountId,
        UUID marginId,
        String detail
) {

    // 불일치 발생 대상 ID: 증거금 단위 검사는 marginId, 계좌 단위 검사는 accountId
    public UUID targetId() {
        return marginId != null ? marginId : accountId;
    }
}
