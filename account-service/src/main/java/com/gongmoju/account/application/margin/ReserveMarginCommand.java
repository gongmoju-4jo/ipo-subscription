package com.gongmoju.account.application.margin;

import java.util.UUID;

/**
 * 증거금 인출 처리에 필요한 요청 정보.
 * Consumer에서 이벤트 형식을 검증한 후 생성한다.
 *
 * @param eventId        이벤트 고유 ID (중복 처리 식별)
 * @param subscriptionId 인출 대상 청약 ID
 * @param userId         청약 요청 사용자 ID (계좌 소유권 검증)
 * @param accountId      증거금 인출 대상 계좌 ID
 * @param ipoId          청약 대상 공모주 ID
 * @param amount         인출할 증거금 금액
 */
public record ReserveMarginCommand(
        UUID eventId,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long amount
) {
}
