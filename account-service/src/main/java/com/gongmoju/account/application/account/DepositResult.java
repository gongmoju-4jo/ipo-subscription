package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.ledger.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

// 입금 결과. 잔고는 원장의 거래 후 잔고라서 재요청에도 처음 응답과 같은 값이 나온다
// created: 이번 요청으로 입금했으면 true(201), 같은 requestId의 재요청이면 false(200)
public record DepositResult(
        UUID depositId,
        UUID accountId,
        long amount,
        long cashBalance,
        long marginBalance,
        Instant createdAt,
        boolean created
) {

    static DepositResult of(LedgerEntry entry, boolean created) {
        return new DepositResult(
                entry.getId(),
                entry.getAccount().getId(),
                entry.getAmount(),
                entry.getCashBalanceAfter(),
                entry.getMarginBalanceAfter(),
                entry.getCreatedAt(),
                created);
    }
}
