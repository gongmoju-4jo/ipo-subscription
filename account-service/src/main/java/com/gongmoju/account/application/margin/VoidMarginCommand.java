package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.margin.MarginVoidReason;

import java.util.UUID;

/**
 * 증거금 반환 처리에 필요한 요청 정보.
 * Consumer에서 이벤트 형식을 검증한 후 생성한다.
 *
 * @param eventId        이벤트 고유 ID (멱등키)
 * @param subscriptionId 반환 대상 청약 ID
 * @param accountId      반환 대상 계좌 ID
 * @param reason         증거금 반환 요청 사유
 */
public record VoidMarginCommand(
        UUID eventId,
        UUID subscriptionId,
        UUID accountId,
        MarginVoidReason reason
) {
}
