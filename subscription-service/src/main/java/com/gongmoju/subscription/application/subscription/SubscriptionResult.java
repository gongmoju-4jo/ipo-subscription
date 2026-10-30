package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 청약 조회 결과. 트랜잭션 밖(컨트롤러)에서 엔티티를 다루지 않도록 값만 복사해 둔다.
 * 해당 단계 전이면 null인 값: 사유(rejectReason, cancelReason), 배정(allocatedQuantity, allocatedAmount), 환불(refundAmount), 확정 시각
 */
public record SubscriptionResult(
        UUID subscriptionId,
        UUID requestId,
        UUID ipoId,
        String ipoName,
        UUID accountId,
        long requestedQuantity,
        long marginAmount,
        SubscriptionStatus status,
        RejectReason rejectReason,
        CancelReason cancelReason,
        Long allocatedQuantity,
        Long allocatedAmount,
        Long refundAmount,
        Instant receivedAt,
        Instant confirmedAt
) {

    public static SubscriptionResult from(Subscription subscription) {
        return new SubscriptionResult(subscription.getId(), subscription.getRequestId(), subscription.getIpo().getId(),
                subscription.getIpo().getName(), subscription.getAccountId(), subscription.getRequestedQuantity(),
                subscription.getMarginAmount(), subscription.getStatus(), subscription.getRejectReason(),
                subscription.getCancelReason(), subscription.getAllocatedQuantity(), subscription.getAllocatedAmount(),
                subscription.getRefundAmount(), subscription.getReceivedAt(), subscription.getConfirmedAt());
    }
}
