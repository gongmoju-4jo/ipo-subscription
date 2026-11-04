package com.gongmoju.account.infrastructure.messaging;

import java.util.UUID;

/**
 * 증거금 정산 완료 이벤트(margin.settled)의 메시지 본문.
 * eventId, eventType, occurredAt 등 공통 필드는 발행기에서 추가한다.
 *
 * @param subscriptionId 정산 대상 청약 ID
 * @param postedAmount   배정 대금으로 정산한 금액 (원)
 * @param refundAmount   예수금으로 환불한 금액 (원)
 */
record MarginSettledPayload(
        UUID subscriptionId,
        long postedAmount,
        long refundAmount
) {
}
