package com.gongmoju.subscription.presentation.internal;

import com.gongmoju.subscription.application.ipo.IpoResult;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

// 게이트웨이용 청약 중 공모주 항목. 게이트웨이가 대기열 진입 때 "지금 청약 기간 안인지" 판단하는 데 필요한 값만 담는다
@Schema(description = "청약 중(OPEN) 공모주 항목")
public record OpenIpoResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "청약 시작 시각 (KST)", example = "2026-11-11T09:00:00+09:00")
        OffsetDateTime subscriptionStartAt,
        @Schema(description = "청약 마감 시각 (KST)", example = "2026-11-12T16:00:00+09:00")
        OffsetDateTime subscriptionEndAt
) {

    public static OpenIpoResponse from(IpoResult result) {
        return new OpenIpoResponse(result.ipoId(), KstTime.of(result.subscriptionStartAt()), KstTime.of(result.subscriptionEndAt()));
    }
}
