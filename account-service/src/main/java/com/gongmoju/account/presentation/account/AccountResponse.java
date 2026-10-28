package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.AccountResult;
import com.gongmoju.account.presentation.support.RequestUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "계좌")
public record AccountResponse(
        @Schema(description = "계좌 ID", example = "9d4e0000-0000-4000-8000-000000000002")
        UUID accountId,
        @Schema(description = "계좌번호 (화면 표시용, 숫자 12자리). 다른 API는 계속 accountId를 쓴다", example = "483920175364")
        String accountNumber,
        @Schema(description = "소유자(회원) ID", example = "7a1c0000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "예수금 (원, 출금 가능액)", example = "1000000")
        long cashBalance,
        @Schema(description = "청약증거금 (원)", example = "0")
        long marginBalance,
        @Schema(description = "개설 시각 (KST)", example = "2026-11-03T10:05:00+09:00")
        OffsetDateTime createdAt
) {

    public static AccountResponse from(AccountResult result) {
        return new AccountResponse(result.accountId(), result.accountNumber(), result.userId(), result.cashBalance(),
                result.marginBalance(), RequestUser.toKst(result.createdAt()));
    }
}
