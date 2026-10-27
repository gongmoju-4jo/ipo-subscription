package com.gongmoju.account.domain.ledger;

// 원장 거래 유형. 방향(+, -)은 유형으로 판단
public enum LedgerEntryType {
    DEPOSIT,
    MARGIN_PENDING,
    MARGIN_POST,
    MARGIN_VOID,
    REFUND
}
