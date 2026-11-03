package com.gongmoju.account.domain.margin;

import java.util.Objects;
import java.util.UUID;

/**
 * 증거금 반환 처리 결과.
 *
 * @param subscriptionId 관련 청약 ID
 * @param marginId       관련 증거금 ID
 * @param accountId      계좌 ID
 * @param amount         실제 반환 금액
 */
public record MarginVoided(
        UUID subscriptionId,
        UUID marginId,
        UUID accountId,
        long amount
) {

    // margin 없이 받은 반환이면 null이 된다.
    public MarginVoided {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(accountId, "accountId");
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "반환 금액은 0 이상이어야 합니다. amount=" + amount
            );
        }
    }
}
