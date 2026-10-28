package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.ledger.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * 가상 입금 처리 결과.
 * 원장의 거래 후 잔고를 사용하여 재요청에도 최초 처리 결과를 반환한다.
 * created는 신규 입금이면 true(201), 중복 요청이면 false(200)이다.
 */
public record DepositResult(
        UUID depositId,
        UUID accountId,
        long amount,
        long cashBalance,
        long marginBalance,
        Instant createdAt,
        boolean created
) {

    /**
     * 원장 기록을 입금 처리 결과로 변환한다.
     *
     * @param entry   입금 원장 기록
     * @param created 신규 입금 여부
     * @return 입금 처리 결과
     */
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
