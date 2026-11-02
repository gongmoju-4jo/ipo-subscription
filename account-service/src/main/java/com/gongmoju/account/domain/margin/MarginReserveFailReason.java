package com.gongmoju.account.domain.margin;

// margin.reserve-failed의 reason (이벤트 형식 v1.1). 청약의 reject_reason과 같은 값
public enum MarginReserveFailReason {
    INSUFFICIENT_BALANCE, // 예수금 부족
    ACCOUNT_NOT_FOUND // 계좌 없음 또는 소유자 다름 (타인 계좌의 존재 여부를 숨기려고 같은 값)
}
