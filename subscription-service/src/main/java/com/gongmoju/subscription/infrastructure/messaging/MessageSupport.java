package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Consumer 메시지의 파싱과 필수 필드 검증 공통 기능. 실패는 모두 재처리해도 결과가 같으므로 {@link InvalidEventException}으로 던져 재시도 없이 DLT로 보냄.
 */
final class MessageSupport {

    private MessageSupport() {
    }

    /**
     * 메시지 본문을 지정한 타입으로 읽음. 모르는 필드는 무시함 (이벤트 명세: 필드 추가 허용).
     *
     * @throws InvalidEventException 본문이 비었거나, JSON 객체가 아니거나, 읽을 수 없는 경우
     */
    static <T> T read(ObjectMapper objectMapper, String payload, Class<T> type) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("메시지 본문이 비어 있습니다.");
        }
        try {
            T message = objectMapper.readValue(payload, type);
            if (message == null) {
                throw new InvalidEventException("메시지 본문이 JSON 객체가 아닙니다.");
            }
            return message;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("메시지 JSON을 읽을 수 없습니다: " + e.getOriginalMessage(), e);
        }
    }

    static void require(Object value, String field) {
        if (value == null) {
            throw new InvalidEventException("필수 필드가 없습니다: " + field);
        }
    }

    static Instant parseTime(String value, String field) {
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
