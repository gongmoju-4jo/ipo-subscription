package com.gongmoju.subscription.domain.subscription;

import java.util.UUID;

/**
 * 증거금 해제 완료 응답 내용. 계좌 서비스가 {@code margin.voided}로 발행한다 (필드는 이벤트 명세 {@code margin.voided} 절).
 *
 * @param eventId 이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param amount  실제로 해제한 금액 (원, 0 이상). 확보한 증거금이 없었으면 0 (시간 초과 해제)
 */
public record MarginVoided(
        UUID eventId,
        UUID subscriptionId,
        long amount
) {
}
