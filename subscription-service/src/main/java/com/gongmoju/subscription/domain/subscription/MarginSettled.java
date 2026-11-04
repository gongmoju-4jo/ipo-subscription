package com.gongmoju.subscription.domain.subscription;

import java.util.UUID;

/**
 * 청약 1건의 증거금 정산 결과. 계좌 서비스가 정산(배정 대금 차감, 나머지 환불)을 마치고 {@code margin.settled}로 발행한다 (필드는 이벤트 명세 {@code margin.settled} 절).
 * 0주 배정과 부적격도 같은 이벤트로 온다 ({@code postedAmount = 0}, {@code refundAmount} = 증거금 전액).
 *
 * @param eventId        이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param subscriptionId 청약 ID
 * @param postedAmount   정산 금액 = 배정 대금 (원, 0 이상)
 * @param refundAmount   환불액 (원, 0 이상). 계좌 서비스가 증거금 − 정산 금액으로 계산함 (D-15)
 */
public record MarginSettled(
        UUID eventId,
        UUID subscriptionId,
        long postedAmount,
        long refundAmount
) {
}
