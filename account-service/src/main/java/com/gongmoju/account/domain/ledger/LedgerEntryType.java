package com.gongmoju.account.domain.ledger;

/**
 * 계좌의 예수금 및 청약증거금 변동 내역을 기록하는 원장 거래 유형.
 * 거래 유형별로 금액 변경 목적과 잔고 반영 방식(예수금, 청약증거금 증가, 차감)을 구분한다.
 */
public enum LedgerEntryType {
    DEPOSIT, // 가상입금
    MARGIN_PENDING, // 청약증거금 인출: 예수금 감소, 증거금 증가
    MARGIN_POST, // 대금 정산: 증거금 감소
    MARGIN_VOID, // 청약 무효화: 증거금 감소, 예수금 증가
    REFUND // 배정 대금 확정 후 남은 증거금 환불: 증거금 감소, 예수금 증가
}
