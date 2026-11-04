package com.gongmoju.account.infrastructure.messaging;

import java.util.UUID;

/**
 * 증거금 반환 margin.voided 이벤트 본문.
 * 공통 필드는 발행기에서 추가한다.
 *
 * @param subscriptionId 청약 ID
 * @param amount         반환 금액. margin이 없는 경우 0
 */
record MarginVoidedPayload(
        UUID subscriptionId,
        long amount
) {
}
