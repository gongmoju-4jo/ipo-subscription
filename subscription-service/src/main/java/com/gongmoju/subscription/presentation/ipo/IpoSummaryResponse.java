package com.gongmoju.subscription.presentation.ipo;

import com.gongmoju.subscription.application.ipo.IpoResult;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "공모주 목록의 항목")
public record IpoSummaryResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "종목명", example = "테스트 공모주 A")
        String name,
        @Schema(description = "확정 공모가 (원)", example = "15000")
        long offerPrice,
        @Schema(description = "일반청약 물량", example = "100000")
        long retailQuantity,
        @Schema(description = "청약 시작 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime subscriptionStartAt,
        @Schema(description = "청약 마감 시각 (KST)", example = "2026-11-04T16:00:00+09:00")
        OffsetDateTime subscriptionEndAt,
        @Schema(description = "공모주 상태. OPEN이라도 청약 시작 전이거나 마감 시각이 지나면 청약할 수 없다", example = "OPEN")
        IpoStatus status
) {

    public static IpoSummaryResponse from(IpoResult result) {
        return new IpoSummaryResponse(result.ipoId(), result.name(), result.offerPrice(), result.retailQuantity(),
                KstTime.of(result.subscriptionStartAt()), KstTime.of(result.subscriptionEndAt()), result.status());
    }
}
