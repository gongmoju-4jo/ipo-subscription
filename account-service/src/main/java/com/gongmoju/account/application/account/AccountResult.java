package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.account.Account;

import java.time.Instant;
import java.util.UUID;

/**
 * 계좌 조회 결과. 트랜잭션 밖(컨트롤러)에서 지연 로딩이 일어나지 않도록 값만 복사해 둔다.
 */
public record AccountResult(
        UUID accountId,
        String accountNumber,
        UUID userId,
        long cashBalance,
        long marginBalance,
        Instant createdAt
) {

    public static AccountResult from(Account account) {
        return new AccountResult(account.getId(), account.getAccountNumber(), account.getUser().getId(),
                account.getCashBalance(), account.getMarginBalance(), account.getCreatedAt());
    }
}
