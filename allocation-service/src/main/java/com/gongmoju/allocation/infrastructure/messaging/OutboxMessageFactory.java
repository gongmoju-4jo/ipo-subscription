package com.gongmoju.allocation.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

// Outbox 행으로 Kafka 메시지 본문을 만든다. 저장된 payload(도메인 필드)에 공통 필드를 붙인다
// 공통 필드는 모두 컬럼에서 만들기 때문에 재발행해도 eventId, occurredAt이 같다
@Component
class OutboxMessageFactory {

    // 이벤트 명세 시각 규칙: ISO-8601, +09:00, 밀리초 (예: 2026-10-27T15:59:58.123+09:00)
    private static final DateTimeFormatter OCCURRED_AT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final ObjectMapper objectMapper;

    OutboxMessageFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
