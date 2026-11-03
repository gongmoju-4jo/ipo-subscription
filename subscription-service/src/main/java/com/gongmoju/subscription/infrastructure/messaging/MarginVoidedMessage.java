package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionVoidedService;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code margin.voided} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code margin.voided} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * {@code amount}는 누락과 {@code 0}(확보한 증거금이 없었던 시간 초과 해제)을 구분해야 하므로 {@link Long}으로 받음.
 */
public record MarginVoidedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        Long amount
) {

    public static final String TOPIC = SubscriptionVoidedService.TOPIC;

    /**
     * 메시지를 해제 완료 응답 내용으로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 음수 금액, ISO-8601이 아닌 시각은 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함.
     * UUID 형식 오류는 역직렬화 단계에서 걸러짐. 금액 {@code 0}은 허용함.
     *
     * @return 해제 완료 응답 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public MarginVoided toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        require(amount, "amount");
        if (amount < 0) {
            throw new InvalidEventException("amount는 0 이상이어야 합니다: " + amount);
        }
        return new MarginVoided(eventId, subscriptionId, amount);
    }
}
