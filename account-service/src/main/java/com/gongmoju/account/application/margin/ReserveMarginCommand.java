package com.gongmoju.account.application.margin;

import java.util.UUID;

// margin.reserve-requested 본문 (이벤트 형식 초안 v1.1). 형식 검증(필수, UUID, 금액 1 이상)은 Consumer가 먼저 한다
public record ReserveMarginCommand(
        UUID eventId,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long amount
) {
}
