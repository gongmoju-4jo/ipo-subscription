package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionConfirmService;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code margin.reserved} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code margin.reserved} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 시각은 ISO-8601이며 계좌 서비스가 발행한다.
 */
public record MarginReservedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        UUID marginId,
        long amount,
        String reservedAt
) {

    public static final String TOPIC = SubscriptionConfirmService.TOPIC;

    /**
     * 메시지를 확보 성공 응답 내용으로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 금액 1 미만, ISO-8601이 아닌 시각은 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함.
     * UUID 형식 오류는 역직렬화 단계에서 걸러지며, 금액 필드가 누락되면 {@code 0}으로 읽혀 본 메서드에서 검증됨.
     *
     * @return 확보 성공 응답 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public MarginReserved toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        require(marginId, "marginId");
        if (amount < 1) {
            throw new InvalidEventException("amount는 1 이상이어야 합니다: " + amount);
        }
        return new MarginReserved(eventId, subscriptionId, marginId, amount, parseTime(reservedAt, "reservedAt"));
    }
}
