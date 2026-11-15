package com.gongmoju.subscription.domain.subscription;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

// 청약 상태 (상태 전이 문서 1-1)
public enum SubscriptionStatus {
    PENDING,
    CONFIRMED,
    REJECTED,
    COMPENSATING,
    CANCEL_REQUESTED, // 청약 취소 구현시 사용
    CANCELLED,
    ALLOCATED,
    REFUNDED;

    /**
     * 배정 대상인 청약의 상태: 증거금이 확보된 청약({@code CONFIRMED})과, 마감 후 배정, 정산을 받아 다음 상태로 넘어간 청약.
     * 마감 후에는 이 집합이 바뀌지 않음. 배정 완료 확인의 청약 수와 경쟁률(F-15)의 신청 수량이 같은 집합을 셈.
     */
    public static final Set<SubscriptionStatus> ALLOCATION_TARGETS = Collections.unmodifiableSet(EnumSet.of(CONFIRMED, ALLOCATED, REFUNDED));
}
