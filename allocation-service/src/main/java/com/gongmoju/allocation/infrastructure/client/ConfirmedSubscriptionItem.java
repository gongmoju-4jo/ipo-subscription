package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;

import java.util.UUID;

/**
 * 청약 서비스 내부 API 응답 항목 (API 명세 4.1). 배정 계산에 필요한 필드만 받는다 (marginAmount, receivedAt은 받지 않음).
 *
 * @param subscriptionId    청약 ID
 * @param userId            회원 ID (배정 결과 조회 본인 확인용으로 allocation.user_id에 저장)
 * @param accountId         계좌 ID (allocation.result 파티션 키)
 * @param requestedQuantity 청약 수량
 */
record ConfirmedSubscriptionItem(
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        long requestedQuantity
) {

    ConfirmedSubscription toConfirmedSubscription() {
        return new ConfirmedSubscription(subscriptionId, userId, accountId, requestedQuantity);
    }
}
