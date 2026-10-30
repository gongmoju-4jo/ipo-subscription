package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.DepositResult;
import com.gongmoju.account.presentation.support.RequestUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "가상 입금 결과")
public record DepositResponse(
        @Schema(description = "입금 ID (입금 원장 기록의 ID)", example = "1f3a0000-0000-4000-8000-000000000011")
        UUID depositId,
        @Schema(description = "계좌 ID", example = "9d4e0000-0000-4000-8000-000000000002")
        UUID accountId,
        @Schema(description = "입금액 (원)", example = "1000000")
        long amount,
        @Schema(description = "입금 후 예수금 (원)", example = "1000000")
        long cashBalance,
        @Schema(description = "청약증거금 (원). 입금으로 바뀌지 않는다", example = "0")
        long marginBalance,
        @Schema(description = "입금 시각 (KST)", example = "2026-11-03T10:10:00+09:00")
        OffsetDateTime createdAt
) {

    public static DepositResponse from(DepositResult result) {
        return new DepositResponse(result.depositId(), result.accountId(), result.amount(), result.cashBalance(),
                result.marginBalance(), RequestUser.toKst(result.createdAt()));
    }
}
