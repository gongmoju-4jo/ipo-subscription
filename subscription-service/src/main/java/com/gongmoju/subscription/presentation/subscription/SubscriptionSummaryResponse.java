package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.subscription.application.subscription.SubscriptionResult;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "내 청약 목록 항목")
public record SubscriptionSummaryResponse(
        @Schema(description = "청약 ID", example = "e8bd1887-fe8e-378c-8f04-13d9d406e984")
        UUID subscriptionId,
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "종목명", example = "테스트 공모주 A")
        String ipoName,
        @Schema(description = "청약 계좌 ID", example = "9d4e0000-0000-4000-8000-000000000002")
        UUID accountId,
        @Schema(description = "신청 수량", example = "100")
        long requestedQuantity,
        @Schema(description = "필요 증거금 (원)", example = "1500000")
        long marginAmount,
        @Schema(description = "현재 청약 상태", example = "CONFIRMED")
        SubscriptionStatus status,
        @Schema(description = "Gateway 최초 도착 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime receivedAt,
        @Schema(description = "청약 확정 시각 (KST). 미확정이면 null", example = "2026-11-03T10:00:02+09:00", nullable = true)
        OffsetDateTime confirmedAt
) {

    public static SubscriptionSummaryResponse from(SubscriptionResult result) {
        return new SubscriptionSummaryResponse(result.subscriptionId(), result.ipoId(), result.ipoName(),
                result.accountId(), result.requestedQuantity(), result.marginAmount(), result.status(),
                KstTime.of(result.receivedAt()), KstTime.of(result.confirmedAt()));
    }
}
