package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.Subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * 배정용 확정 청약 조회 결과 (API 명세 4.1). 배정 계산과 결과 이벤트에 필요한 값만 복사해 둔다.
 */
public record ConfirmedSubscriptionResult(
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        long requestedQuantity,
        long marginAmount,
        Instant receivedAt
) {

    public static ConfirmedSubscriptionResult from(Subscription subscription) {
        return new ConfirmedSubscriptionResult(subscription.getId(), subscription.getUserId(), subscription.getAccountId(),
                subscription.getRequestedQuantity(), subscription.getMarginAmount(), subscription.getReceivedAt());
    }
}
