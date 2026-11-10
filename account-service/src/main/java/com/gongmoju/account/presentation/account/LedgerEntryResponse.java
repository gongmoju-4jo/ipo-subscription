package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.LedgerEntryResult;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.account.presentation.support.RequestUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "계좌 거래 내역(원장)의 항목")
public record LedgerEntryResponse(
        @Schema(description = "원장 ID", example = "7c1e0000-0000-4000-8000-000000000010")
        UUID ledgerEntryId,
        @Schema(description = "거래 종류 (DEPOSIT 입금, MARGIN_PENDING 증거금 인출, MARGIN_POST 배정 대금 정산, MARGIN_VOID 증거금 반환, REFUND 환불)", example = "MARGIN_PENDING")
        LedgerEntryType type,
        @Schema(description = "거래 금액 (원, 항상 양수. 방향은 type으로 판단)", example = "150000")
        long amount,
        @Schema(description = "거래 후 예수금 (원)", example = "850000")
        long cashBalanceAfter,
        @Schema(description = "거래 후 청약증거금 (원)", example = "150000")
        long marginBalanceAfter,
        @Schema(description = "관련 청약 ID (입금은 없음)", example = "8b2d0000-0000-4000-8000-000000000003", nullable = true)
        UUID subscriptionId,
        @Schema(description = "거래 시각 (KST)", example = "2026-11-06T00:30:00+09:00")
        OffsetDateTime createdAt
) {

    public static LedgerEntryResponse from(LedgerEntryResult result) {
        return new LedgerEntryResponse(result.ledgerEntryId(), result.type(), result.amount(), result.cashBalanceAfter(),
                result.marginBalanceAfter(), result.subscriptionId(), RequestUser.toKst(result.createdAt()));
    }
}
