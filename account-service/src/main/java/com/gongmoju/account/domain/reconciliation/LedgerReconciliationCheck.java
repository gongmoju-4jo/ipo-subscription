package com.gongmoju.account.domain.reconciliation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 원장 대사 검사 종류. 괄호 안 번호는 loadtest/sql/verify.sql의 검사 번호
// 검사를 추가하면 이곳에 값을 추가하고 LedgerReconciliationRepository 구현에 SQL을 추가한다 (switch 누락은 컴파일 오류)
@Getter
@RequiredArgsConstructor
public enum LedgerReconciliationCheck {
    MARGIN_BALANCE("청약증거금 = PENDING 증거금 합계"), // (2번)
    LEDGER_SUM("잔고 = 원장 금액 합계"), // (3번을 합계 방식으로 변경) 원장 순서와 무관하게 비교한다
    MARGIN_PENDING_LEDGER("증거금마다 인출 원장 1줄, 금액 = 증거금"), // (11번) 중복 인출, 원장 누락
    MARGIN_STATUS_LEDGER("증거금 상태별 정산, 환불, 반환 원장 금액"); // (12번)

    private final String description;
}
