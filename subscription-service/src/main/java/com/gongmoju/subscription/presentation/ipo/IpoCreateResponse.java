package com.gongmoju.subscription.presentation.ipo;

import com.gongmoju.subscription.application.ipo.IpoResult;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import com.gongmoju.subscription.presentation.support.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

// 공모주 상세(IpoResponse)와 같은 필드에 등록 시각(createdAt)을 더함. 명세 3.6이 등록 응답에만 createdAt을 둬서 따로 둔다
@Schema(description = "등록한 공모주")
public record IpoCreateResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "종목명", example = "테스트 공모주 A")
        String name,
        @Schema(description = "확정 공모가 (원)", example = "15000")
        long offerPrice,
        @Schema(description = "일반청약 물량", example = "100000")
        long retailQuantity,
        @Schema(description = "균등배정 물량", example = "50000")
        long equalQuantity,
        @Schema(description = "최소 청약 수량", example = "10")
        long minQuantity,
        @Schema(description = "1인 청약 한도", example = "10000")
        long maxQuantity,
        @Schema(description = "증거금률", example = "1.00")
        BigDecimal marginRate,
        @Schema(description = "청약 시작 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime subscriptionStartAt,
        @Schema(description = "청약 마감 시각 (KST)", example = "2026-11-04T16:00:00+09:00")
        OffsetDateTime subscriptionEndAt,
        @Schema(description = "환불 예정일", example = "2026-11-06")
        LocalDate refundDate,
        @Schema(description = "공모주 상태. 등록하면 OPEN", example = "OPEN")
        IpoStatus status,
        @Schema(description = "등록 시각 (KST)", example = "2026-11-02T10:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static IpoCreateResponse from(IpoResult result) {
        return new IpoCreateResponse(result.ipoId(), result.name(), result.offerPrice(), result.retailQuantity(),
                result.equalQuantity(), result.minQuantity(), result.maxQuantity(), result.marginRate(),
                KstTime.of(result.subscriptionStartAt()), KstTime.of(result.subscriptionEndAt()),
                result.refundDate(), result.status(), KstTime.of(result.createdAt()));
    }
}
