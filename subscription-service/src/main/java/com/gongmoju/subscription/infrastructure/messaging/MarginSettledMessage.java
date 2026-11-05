package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionRefundService;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code margin.settled} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code margin.settled} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 금액은 누락과 {@code 0}(0주 배정의 정산 금액, 전부 배정의 환불액)을 구분해야 하므로 {@link Long}으로 받음.
 */
public record MarginSettledMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        Long postedAmount,
        Long refundAmount
) {

    public static final String TOPIC = SubscriptionRefundService.TOPIC;

    /**
     * 메시지를 정산 결과로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 시각 형식 오류, 음수 금액은 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함.
     * UUID 형식 오류는 역직렬화 단계에서 걸러짐. 저장된 청약과 비교해야 하는 검증(배정 대금, 증거금, 공모가, 청약 수량)은 처리 서비스가 함.
     *
     * @return 정산 결과
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public MarginSettled toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        long posted = requireNotNegative(postedAmount, "postedAmount");
        long refund = requireNotNegative(refundAmount, "refundAmount");
        return new MarginSettled(eventId, subscriptionId, posted, refund);
    }

    private static long requireNotNegative(Long value, String field) {
        require(value, field);
        if (value < 0) {
            throw new InvalidEventException(field + "는 0 이상이어야 합니다: " + value);
        }
        return value;
    }
}
