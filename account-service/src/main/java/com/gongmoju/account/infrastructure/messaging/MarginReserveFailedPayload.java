package com.gongmoju.account.infrastructure.messaging;

import java.util.UUID;

/**
 * margin.reserve-failed 이벤트 본문 DTO.
 * 공통 이벤트 필드(eventId, eventType, occurredAt)는
 * Outbox 이벤트 생성 과정에서 추가한다.
 *
 * @param subscriptionId 인출 실패 청약 ID
 * @param reason         인출 실패 사유
 */
record MarginReserveFailedPayload(
        UUID subscriptionId,
        String reason
) {
}
