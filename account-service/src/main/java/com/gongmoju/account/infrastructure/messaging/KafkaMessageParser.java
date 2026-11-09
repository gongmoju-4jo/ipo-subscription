package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.gongmoju.account.global.exception.InvalidEventException;

// Consumer 3개가 같은 방식으로 메시지 본문을 읽는다 (#106에서 각 Consumer의 parse()를 모음)
// 빈 본문, JSON 객체가 아님, 읽기 실패는 재시도해도 결과가 같아서 InvalidEventException으로 바꿔 바로 DLT로 보낸다
// 읽는 방식(소수 금액 거절 등)은 Consumer마다 달라서 ObjectReader를 받는다
final class KafkaMessageParser {

    private KafkaMessageParser() {
    }

    static <T> T parse(String payload, ObjectReader reader) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("메시지 본문이 비어 있습니다.");
        }
        try {
            T message = reader.readValue(payload);
            // 본문이 "null"이면 예외 없이 null이 나온다
            if (message == null) {
                throw new InvalidEventException("메시지 본문이 JSON 객체가 아닙니다.");
            }
            return message;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("메시지 JSON을 읽을 수 없습니다: " + e.getOriginalMessage());
        }
    }
}
