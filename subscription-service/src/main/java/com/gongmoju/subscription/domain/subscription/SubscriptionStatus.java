package com.gongmoju.subscription.domain.subscription;

// 청약 상태 (상태 전이 문서 1-1)
public enum SubscriptionStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    COMPENSATING,
    CANCEL_REQUESTED,
    CANCELLED,
    ALLOCATED,
    REFUNDED
}
