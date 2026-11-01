package com.gongmoju.subscription.domain.subscription;

import java.util.UUID;

/**
 * 증거금 확보 실패 응답 내용. 계좌 서비스가 {@code margin.reserve-failed}로 발행한다 (필드는 이벤트 명세 {@code margin.reserve-failed} 절).
 *
 * @param eventId 이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param reason  거절 사유 ({@code INSUFFICIENT_BALANCE}, {@code ACCOUNT_NOT_FOUND}). 청약의 {@code reject_reason}에 그대로 저장
 */
public record MarginReserveFailed(
        UUID eventId,
        UUID subscriptionId,
        RejectReason reason
) {
}
