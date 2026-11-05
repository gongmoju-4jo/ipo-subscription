package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryType;

import java.time.Instant;
import java.util.UUID;

// 원장 조회 결과. 트랜잭션 밖에서 지연 로딩이 일어나지 않도록 값만 복사한다
public record LedgerEntryResult(
        UUID ledgerEntryId,
        LedgerEntryType type,
        long amount,
        long cashBalanceAfter,
        long marginBalanceAfter,
        UUID subscriptionId,
        Instant createdAt
) {

    public static LedgerEntryResult from(LedgerEntry entry) {
        return new LedgerEntryResult(entry.getId(), entry.getType(), entry.getAmount(), entry.getCashBalanceAfter(),
                entry.getMarginBalanceAfter(), entry.getSubscriptionId(), entry.getCreatedAt());
    }
}
