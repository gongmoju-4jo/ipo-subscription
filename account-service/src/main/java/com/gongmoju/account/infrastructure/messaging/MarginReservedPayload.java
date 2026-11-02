package com.gongmoju.account.infrastructure.messaging;

import java.util.UUID;

// margin.reserved 이벤트 본문 DTO. 공통 이벤트 필드는 발행기에서 추가한다. (eventId, eventType, occurredAt)
record MarginReservedPayload(
        UUID subscriptionId,
        UUID marginId,
        long amount,
        String reservedAt
) {
}
