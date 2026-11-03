package com.gongmoju.account.domain.margin;

// margin.void-requested의 reason (이벤트 형식 v1.1). 청약의 cancel_reason과 같은 값
public enum MarginVoidReason {
    CONFIRM_FAILED, // 확정 저장 실패
    TIMEOUT, // 계좌 응답 없음
    USER_CANCEL // 사용자 취소
}
