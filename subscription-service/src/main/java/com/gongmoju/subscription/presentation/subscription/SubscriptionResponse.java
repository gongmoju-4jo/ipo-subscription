package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.subscription.application.subscription.SubscriptionResult;
import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "청약 상세, 상태 (증거금, 배정, 환불 결과 포함)")
public record SubscriptionResponse(
        @Schema(description = "청약 ID", example = "e8bd1887-fe8e-378c-8f04-13d9d406e984")
        UUID subscriptionId,
        @Schema(description = "최초 요청 ID", example = "3b8f0000-0000-4000-8000-000000000031")
        UUID requestId,
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "종목명", example = "테스트 공모주 A")
        String ipoName,
        @Schema(description = "청약 계좌 ID", example = "9d4e0000-0000-4000-8000-000000000002")
        UUID accountId,
        @Schema(description = "청약 수량", example = "100")
        long requestedQuantity,
        @Schema(description = "필요 증거금 (원)", example = "1500000")
        long marginAmount,
        @Schema(description = "청약 상태", example = "REFUNDED")
        SubscriptionStatus status,
        @Schema(description = "거절 사유. REJECTED가 아니면 null", example = "INSUFFICIENT_BALANCE", nullable = true)
        RejectReason rejectReason,
        @Schema(description = "보상, 취소 사유. 해당 없으면 null", example = "USER_CANCEL", nullable = true)
        CancelReason cancelReason,
        @Schema(description = "배정 수량 (0주 가능). 배정 전 null", example = "3", nullable = true)
        Long allocatedQuantity,
        @Schema(description = "배정 대금 (원). 배정 전 null", example = "45000", nullable = true)
        Long allocatedAmount,
        @Schema(description = "실제 환불액 (원). 정산 전 null", example = "1455000", nullable = true)
        Long refundAmount,
        @Schema(description = "Gateway 도착 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime receivedAt,
        @Schema(description = "청약 확정 시각 (KST). 미확정이면 null", example = "2026-11-03T10:00:02+09:00", nullable = true)
        OffsetDateTime confirmedAt
) {

    public static SubscriptionResponse from(SubscriptionResult result) {
        return new SubscriptionResponse(result.subscriptionId(), result.requestId(), result.ipoId(), result.ipoName(),
                result.accountId(), result.requestedQuantity(), result.marginAmount(), result.status(),
                result.rejectReason(), result.cancelReason(), result.allocatedQuantity(), result.allocatedAmount(),
                result.refundAmount(), KstTime.of(result.receivedAt()), KstTime.of(result.confirmedAt()));
    }
}
