package com.gongmoju.subscription.domain.subscription;

// 해제 사유. COMPENSATING, CANCEL_REQUESTED, CANCELLED일 때 필수. 이벤트 margin.void-requested의 reason과 같은 값
public enum CancelReason {
    CONFIRM_FAILED,
    TIMEOUT,
    USER_CANCEL
}
