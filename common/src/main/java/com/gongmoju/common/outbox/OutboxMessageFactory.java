package com.gongmoju.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * {@link Outbox} 행으로 Kafka 메시지 본문을 만든다. 저장된 payload(도메인 필드)에 공통 필드를 붙인다.
 * 공통 필드는 모두 저장된 컬럼에서 만들기 때문에, 같은 행을 다시 발행해도 eventId와 occurredAt이 같다.
 */
class OutboxMessageFactory {

    // 이벤트 명세 시각 규칙: ISO-8601, +09:00, 밀리초 (예: 2026-10-27T15:59:58.123+09:00)
    private static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;

    OutboxMessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 메시지 본문을 만든다. 공통 필드는 {@code eventId}(outbox_id), {@code eventType}, {@code occurredAt}(created_at을 KST로 변환)이다.
     * payload에 같은 이름의 필드가 있어도 공통 필드를 덮어쓰지 않는다.
     *
     * @param outbox 발행할 행
     * @return Kafka로 보낼 JSON 문자열
     * @throws IllegalStateException payload를 읽을 수 없거나 JSON 객체가 아닌 경우
     */
    String create(Outbox outbox) {
        JsonNode payload = readPayload(outbox);
        ObjectNode message = objectMapper.createObjectNode();
        message.put("eventId", outbox.getId().toString());
        message.put("eventType", outbox.getEventType());
        message.put("occurredAt", OCCURRED_AT_FORMAT.format(OffsetDateTime.ofInstant(outbox.getCreatedAt(), KST)));
        // 공통 필드가 우선한다 (payload에 같은 이름이 있어도 덮어쓰지 않는다)
        payload.fields().forEachRemaining(field -> {
            if (!message.has(field.getKey())) {
                message.set(field.getKey(), field.getValue());
            }
        });
        return message.toString();
    }

    /**
     * 저장된 payload 문자열을 JSON 객체로 읽는다.
     */
    private JsonNode readPayload(Outbox outbox) {
        try {
            JsonNode payload = objectMapper.readTree(outbox.getPayload());
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("outbox payload가 JSON 객체가 아닙니다. outboxId=" + outbox.getId());
            }
            return payload;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload를 읽을 수 없습니다. outboxId=" + outbox.getId(), e);
        }
    }
}
