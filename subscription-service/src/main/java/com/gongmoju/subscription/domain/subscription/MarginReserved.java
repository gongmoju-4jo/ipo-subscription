package com.gongmoju.subscription.domain.subscription;

import java.time.Instant;
import java.util.UUID;

/**
 * 증거금 확보 성공 응답 내용. 계좌 서비스가 {@code margin.reserved}로 발행한다 (필드는 이벤트 명세 {@code margin.reserved} 절).
 *
 * @param eventId    이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param marginId   계좌 서비스의 증거금 ID. 청약의 {@code margin_id}에 저장
 * @param amount     확보한 금액 (원). 1 이상
 * @param reservedAt 확보 시각. 청약의 {@code confirmed_at}에 저장
 */
public record MarginReserved(
        UUID eventId,
        UUID subscriptionId,
        UUID marginId,
        long amount,
        Instant reservedAt
) {
}
