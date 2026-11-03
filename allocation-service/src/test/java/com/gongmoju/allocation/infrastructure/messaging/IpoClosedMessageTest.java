package com.gongmoju.allocation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import com.gongmoju.allocation.global.exception.InvalidEventException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// ipo.closed 메시지 변환과 검증 (이벤트 명세 ipo.closed 절의 필수 필드, 범위)
class IpoClosedMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // 명세 예시 메시지를 그대로 마감 내용으로 바꾼다. 모르는 필드는 무시한다
    @Test
    void convertsValidMessage() {
        ObjectNode node = validNode();
        node.put("unknownField", "무시");

        IpoClosed event = read(node).toEvent();

        assertThat(event.eventId()).isEqualTo(UUID.fromString("0192c1a3-0008-7d00-8a00-000000000008"));
        assertThat(event.ipoId()).isEqualTo(UUID.fromString("0192b9f0-1111-7aaa-8bbb-000000000001"));
        assertThat(event.closedAt()).isEqualTo(Instant.parse("2026-10-27T07:00:00Z"));
        assertThat(event.confirmedCount()).isEqualTo(1000);
        assertThat(event.offerPrice()).isEqualTo(15000);
        assertThat(event.retailQuantity()).isEqualTo(100000);
        assertThat(event.equalQuantity()).isEqualTo(50000);
        assertThat(event.minQuantity()).isEqualTo(10);
    }

    // 경계값: 확정 0건, 균등 물량 0 또는 일반청약 물량과 같음, 공모가, 물량, 최소 수량 1
    @ParameterizedTest
    @CsvSource({"0, 1, 1, 0, 1", "0, 1, 1, 1, 1", "5, 1, 10, 10, 1"})
    void acceptsBoundaryValues(long confirmedCount, long offerPrice, long retailQuantity, long equalQuantity, long minQuantity) {
        ObjectNode node = validNode();
        node.put("confirmedCount", confirmedCount);
        node.put("offerPrice", offerPrice);
        node.put("retailQuantity", retailQuantity);
        node.put("equalQuantity", equalQuantity);
        node.put("minQuantity", minQuantity);

        IpoClosed event = read(node).toEvent();

        assertThat(event.equalQuantity()).isEqualTo(equalQuantity);
    }

    // 필수 필드가 없으면 재시도 없이 DLT로 보낸다
    @ParameterizedTest
    @ValueSource(strings = {"eventId", "eventType", "occurredAt", "ipoId", "closedAt", "confirmedCount",
            "offerPrice", "retailQuantity", "equalQuantity", "minQuantity"})
    void rejectsMissingField(String field) {
        ObjectNode node = validNode();
        node.remove(field);

        assertThatThrownBy(() -> read(node).toEvent())
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }

    // 명세 범위를 벗어난 숫자는 재시도 없이 DLT로 보낸다
    @ParameterizedTest
    @CsvSource({"confirmedCount, -1", "offerPrice, 0", "retailQuantity, 0", "equalQuantity, -1", "minQuantity, 0"})
    void rejectsOutOfRangeNumber(String field, long value) {
        ObjectNode node = validNode();
        node.put(field, value);

        assertThatThrownBy(() -> read(node).toEvent())
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }

    // 균등배정 물량은 일반청약 물량을 넘을 수 없다
    @Test
    void rejectsEqualQuantityAboveRetailQuantity() {
        ObjectNode node = validNode();
        node.put("equalQuantity", 100001);

        assertThatThrownBy(() -> read(node).toEvent())
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("equalQuantity");
    }

    // eventType이 토픽 이름과 다르면 재시도 없이 DLT로 보낸다
    @Test
    void rejectsWrongEventType() {
        ObjectNode node = validNode();
        node.put("eventType", "allocation.finished");

        assertThatThrownBy(() -> read(node).toEvent())
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("eventType");
    }

    // 시각 형식이 틀리면 재시도 없이 DLT로 보낸다
    @Test
    void rejectsInvalidTime() {
        ObjectNode node = validNode();
        node.put("closedAt", "2026-10-27 16:00:00");

        assertThatThrownBy(() -> read(node).toEvent())
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("closedAt");
    }

    // JSON이 아니거나 UUID 형식이 틀리면 읽는 단계에서 재시도 없이 DLT로 보낸다
    @Test
    void rejectsUnreadablePayload() {
        ObjectNode node = validNode();
        node.put("ipoId", "not-a-uuid");

        assertThatThrownBy(() -> MessageSupport.read(objectMapper, node.toString(), IpoClosedMessage.class))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> MessageSupport.read(objectMapper, "{", IpoClosedMessage.class))
                .isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> MessageSupport.read(objectMapper, "", IpoClosedMessage.class))
                .isInstanceOf(InvalidEventException.class);
    }

    private IpoClosedMessage read(ObjectNode node) {
        return MessageSupport.read(objectMapper, node.toString(), IpoClosedMessage.class);
    }

    // 이벤트 명세 ipo.closed 절의 예시
    private ObjectNode validNode() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventId", "0192c1a3-0008-7d00-8a00-000000000008");
        node.put("eventType", "ipo.closed");
        node.put("occurredAt", "2026-10-27T16:01:00.000+09:00");
        node.put("ipoId", "0192b9f0-1111-7aaa-8bbb-000000000001");
        node.put("closedAt", "2026-10-27T16:00:00.000+09:00");
        node.put("confirmedCount", 1000);
        node.put("offerPrice", 15000);
        node.put("retailQuantity", 100000);
        node.put("equalQuantity", 50000);
        node.put("minQuantity", 10);
        return node;
    }
}
