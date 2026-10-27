package com.gongmoju.subscription.domain.subscription;

// 청약 상태 (상태 전이 문서 1-1)
public enum SubscriptionStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    COMPENSATING,
    CANCEL_REQUESTED, // 청약 취소 구현시 사용
    CANCELLED,
    ALLOCATED,
    REFUNDED
}
