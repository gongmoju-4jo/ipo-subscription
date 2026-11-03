package com.gongmoju.account.application.margin;

import java.util.UUID;

/**
 * 증거금 정산 처리에 필요한 요청 정보.
 * <p>
 * Consumer에서 이벤트 형식을 검증한 후 생성한다.
 *
 * @param eventId         이벤트 고유 ID (이벤트 중복 처리용 멱등키)
 * @param subscriptionId  정산 대상 청약 ID
 * @param accountId       정산 대상 계좌 ID
 * @param allocatedAmount 배정된 주식의 총 대금 (원)
 */
public record SettleMarginCommand(
        UUID eventId,
        UUID subscriptionId,
        UUID accountId,
        long allocatedAmount
) {
}
