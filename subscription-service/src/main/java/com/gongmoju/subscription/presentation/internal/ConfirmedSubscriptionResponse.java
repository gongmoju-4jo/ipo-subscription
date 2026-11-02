package com.gongmoju.subscription.presentation.internal;

import com.gongmoju.subscription.application.subscription.ConfirmedSubscriptionResult;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "배정용 확정 청약 항목")
public record ConfirmedSubscriptionResponse(
        @Schema(description = "청약 ID", example = "e8bd1887-fe8e-378c-8f04-13d9d406e984")
        UUID subscriptionId,
        @Schema(description = "청약한 회원 ID. 배정 결과 조회에서 본인 확인에 사용", example = "7a1c0000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "청약 계좌 ID. allocation.result의 파티션 키", example = "9d4e0000-0000-4000-8000-000000000002")
        UUID accountId,
        @Schema(description = "신청 수량. 균등 자격, 비례 계산에 사용", example = "100")
        long requestedQuantity,
        @Schema(description = "증거금 (원). 배정 대금 상한 확인용", example = "1500000")
        long marginAmount,
        @Schema(description = "Gateway 최초 도착 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime receivedAt
) {

    public static ConfirmedSubscriptionResponse from(ConfirmedSubscriptionResult result) {
        return new ConfirmedSubscriptionResponse(result.subscriptionId(), result.userId(), result.accountId(),
                result.requestedQuantity(), result.marginAmount(), KstTime.of(result.receivedAt()));
    }
}
