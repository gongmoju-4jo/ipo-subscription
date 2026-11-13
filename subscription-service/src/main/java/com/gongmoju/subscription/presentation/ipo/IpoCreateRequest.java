package com.gongmoju.subscription.presentation.ipo;

import com.gongmoju.subscription.application.ipo.IpoRegisterCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

// 여기서는 값이 있는지와 종목명 길이만 확인한다. 범위와 필드끼리의 관계(물량, 수량, 증거금률, 기간, 환불일)는 도메인 Ipo.register가 확인한다
@Schema(description = "공모주 등록 요청")
public record IpoCreateRequest(
        @Schema(description = "종목명, 1~30자", example = "테스트 공모주 A")
        @NotBlank(message = "필수값입니다.")
        @Size(max = 30, message = "30자 이하여야 합니다.")
        String name,

        @Schema(description = "확정 공모가 (원), 1 이상", example = "15000")
        @NotNull(message = "필수값입니다.")
        Long offerPrice,

        @Schema(description = "일반청약 물량, 1 이상", example = "100000")
        @NotNull(message = "필수값입니다.")
        Long retailQuantity,

        @Schema(description = "균등배정 물량. 일반청약 물량의 절반 이상, 전체 이하", example = "50000")
        @NotNull(message = "필수값입니다.")
        Long equalQuantity,

        @Schema(description = "최소 청약 수량. 없으면 10, 1 이상", example = "10", nullable = true)
        Long minQuantity,

        @Schema(description = "1인 청약 한도. 최소 청약 수량 이상", example = "10000")
        @NotNull(message = "필수값입니다.")
        Long maxQuantity,

        @Schema(description = "증거금률. 없으면 1.00. 청약 접수가 100%만 처리해서 1.00만 받음 (D-18)", example = "1.00", nullable = true)
        BigDecimal marginRate,

        @Schema(description = "청약 시작 시각 (시간대 포함). 이미 지난 시각도 받음", example = "2026-11-03T10:00:00+09:00")
        @NotNull(message = "필수값입니다.")
        OffsetDateTime subscriptionStartAt,

        @Schema(description = "청약 마감 시각 (시간대 포함). 시작 시각과 지금보다 뒤", example = "2026-11-04T16:00:00+09:00")
        @NotNull(message = "필수값입니다.")
        OffsetDateTime subscriptionEndAt,

        @Schema(description = "환불 예정일. 청약 마감일(KST)보다 뒤", example = "2026-11-06")
        @NotNull(message = "필수값입니다.")
        LocalDate refundDate
) {

    // 선택값 기본값 (API 명세 3.6)
    static final long DEFAULT_MIN_QUANTITY = 10;
    static final BigDecimal DEFAULT_MARGIN_RATE = new BigDecimal("1.00");

    public IpoRegisterCommand toCommand() {
        return new IpoRegisterCommand(name, offerPrice, retailQuantity, equalQuantity,
                minQuantity == null ? DEFAULT_MIN_QUANTITY : minQuantity, maxQuantity,
                marginRate == null ? DEFAULT_MARGIN_RATE : marginRate,
                subscriptionStartAt.toInstant(), subscriptionEndAt.toInstant(), refundDate);
    }
}
