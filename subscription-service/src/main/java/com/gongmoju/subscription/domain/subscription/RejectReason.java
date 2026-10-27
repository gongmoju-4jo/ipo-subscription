package com.gongmoju.subscription.domain.subscription;

// 거절 사유. REJECTED일 때만
public enum RejectReason {
    ALREADY_SUBSCRIBED,
    INSUFFICIENT_BALANCE,
    ACCOUNT_NOT_FOUND
}
