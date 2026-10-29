package com.gongmoju.subscription.application.subscription;

import java.util.UUID;

/**
 * 청약 신청 입력. 회원 ID와 도착 시각은 요청 본문이 아니라 Gateway 헤더에서 받으므로 따로 넘긴다.
 */
public record SubscribeCommand(
        UUID requestId,
        UUID ipoId,
        UUID accountId,
        long requestedQuantity
) {
}
