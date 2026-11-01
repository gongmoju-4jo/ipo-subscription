package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * {@code subscription.requested} 메시지 본문 (이벤트 명세 1장 공통 필드 + 2-1). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 시각은 ISO-8601 {@code +09:00}, 밀리초 3자리로 고정한다 (예: {@code 2026-10-27T15:59:58.123+09:00}).
 */
public record SubscriptionRequestedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID requestId,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long requestedQuantity,
        String receivedAt
) {

    public static final String TOPIC = "subscription.requested";

    private static final DateTimeFormatter KST_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.ofHours(9));

    /**
     * @param event      접수 내용
     * @param eventId    메시지 고유 ID. Outbox를 쓰지 않으므로 발행할 때 새로 만든다 (재요청 중복은 requestId로 판단)
     * @param occurredAt 발행 시각
     */
    public static SubscriptionRequestedMessage of(SubscriptionRequested event, UUID eventId, Instant occurredAt) {
        return new SubscriptionRequestedMessage(eventId, TOPIC, KST_MILLIS.format(occurredAt),
                event.requestId(), event.subscriptionId(), event.userId(), event.accountId(), event.ipoId(),
                event.requestedQuantity(), KST_MILLIS.format(event.receivedAt()));
    }

    /**
     * 메시지를 접수 내용으로 변환함. 변환 과정이 이벤트 명세 2-1의 필수 필드와 형식 검증에 해당함.
     * 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락, {@code eventType}과 토픽 이름 불일치,
     * 수량 1 미만, ISO-8601이 아닌 시각은 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함.
     * UUID 형식 오류는 역직렬화 단계에서 걸러지며, 수량 필드가 누락되면 {@code 0}으로 읽혀 본 메서드에서 검증됨.
     *
     * @return 접수 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public SubscriptionRequested toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(requestId, "requestId");
        require(subscriptionId, "subscriptionId");
        require(userId, "userId");
        require(accountId, "accountId");
        require(ipoId, "ipoId");
        if (requestedQuantity < 1) {
            throw new InvalidEventException("requestedQuantity는 1 이상이어야 합니다: " + requestedQuantity);
        }
        Instant received = parseTime(receivedAt, "receivedAt");
        return new SubscriptionRequested(requestId, subscriptionId, userId, accountId, ipoId, requestedQuantity, received);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new InvalidEventException("필수 필드가 없습니다: " + field);
        }
    }

    private static Instant parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("필수 필드가 없습니다: " + field);
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("시각 형식이 올바르지 않습니다: " + field + "=" + value);
        }
    }
}
