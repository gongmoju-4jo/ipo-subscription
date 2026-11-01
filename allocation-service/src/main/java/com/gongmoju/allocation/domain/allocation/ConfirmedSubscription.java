package com.gongmoju.allocation.domain.allocation;

import java.util.UUID;

/**
 * 배정 대상인 확정(CONFIRMED) 청약 1건. 청약 서비스에서 조회한 값을 배정 계산에 넘길 때 쓴다.
 *
 * @param subscriptionId    청약 ID
 * @param userId            회원 ID
 * @param accountId         계좌 ID
 * @param requestedQuantity 청약 수량
 */
public record ConfirmedSubscription(
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        long requestedQuantity
) {
}
