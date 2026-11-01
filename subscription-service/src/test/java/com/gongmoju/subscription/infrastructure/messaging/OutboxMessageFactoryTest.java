package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboxMessageFactory: Outbox 행으로 Kafka 메시지 본문 만들기")
class OutboxMessageFactoryTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OutboxMessageFactory factory;

    @BeforeEach
    void setUp() {
        factory = new OutboxMessageFactory(objectMapper);
    }

    @Test
    @DisplayName("공통 필드(eventId, eventType, occurredAt)에 payload의 도메인 필드를 합친다")
    void create_mergesCommonFieldsAndPayload() throws Exception {
        // given
        UUID outboxId = UUID.randomUUID();
        Outbox outbox = outbox(outboxId, "margin.reserve-requested", "{\"accountId\":\"a-1\",\"marginAmount\":50000}", CREATED_AT);

        // when
        JsonNode message = objectMapper.readTree(factory.create(outbox));

        // then
        assertThat(message.get("eventId").asText()).isEqualTo(outboxId.toString());
        assertThat(message.get("eventType").asText()).isEqualTo("margin.reserve-requested");
        assertThat(message.get("occurredAt").asText()).isEqualTo("2026-10-27T15:59:58.123+09:00");
        assertThat(message.get("accountId").asText()).isEqualTo("a-1");
        assertThat(message.get("marginAmount").asLong()).isEqualTo(50000L);
    }

    @Test
    @DisplayName("occurredAt은 밀리초가 0이어도 3자리로 채운다")
    void create_padsMillisecondsOfOccurredAt() throws Exception {
        // given
        Outbox outbox = outbox(UUID.randomUUID(), "margin.reserve-requested", "{}", Instant.parse("2026-10-27T06:59:58Z"));

        // when
        JsonNode message = objectMapper.readTree(factory.create(outbox));

        // then
        assertThat(message.get("occurredAt").asText()).isEqualTo("2026-10-27T15:59:58.000+09:00");
    }

    @Test
    @DisplayName("같은 행으로 여러 번 만들어도 메시지가 같다 (재발행해도 eventId, occurredAt 동일)")
    void create_isDeterministicForSameRow() {
        // given
        Outbox outbox = outbox(UUID.randomUUID(), "margin.reserve-requested", "{\"accountId\":\"a-1\"}", CREATED_AT);

        // when
        String first = factory.create(outbox);
        String second = factory.create(outbox);

        // then
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("payload에 공통 필드와 같은 이름이 있어도 공통 필드를 덮어쓰지 않는다")
    void create_commonFieldsTakePrecedenceOverPayload() throws Exception {
        // given
        UUID outboxId = UUID.randomUUID();
        Outbox outbox = outbox(outboxId, "margin.reserve-requested",
                "{\"eventId\":\"fake\",\"eventType\":\"fake\",\"occurredAt\":\"fake\",\"accountId\":\"a-1\"}", CREATED_AT);

        // when
        JsonNode message = objectMapper.readTree(factory.create(outbox));

        // then
        assertThat(message.get("eventId").asText()).isEqualTo(outboxId.toString());
        assertThat(message.get("eventType").asText()).isEqualTo("margin.reserve-requested");
        assertThat(message.get("occurredAt").asText()).isEqualTo("2026-10-27T15:59:58.123+09:00");
        assertThat(message.get("accountId").asText()).isEqualTo("a-1");
    }

    @Test
    @DisplayName("payload가 JSON 형식이 아니면 IllegalStateException을 던진다")
    void create_throwsWhenPayloadIsInvalidJson() {
        // given
        Outbox outbox = outbox(UUID.randomUUID(), "margin.reserve-requested", "{not-json", CREATED_AT);

        // when, then
        assertThatThrownBy(() -> factory.create(outbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outboxId=" + outbox.getId());
    }

    @Test
    @DisplayName("payload가 JSON 객체가 아니면(배열) IllegalStateException을 던진다")
    void create_throwsWhenPayloadIsNotJsonObject() {
        // given
        Outbox outbox = outbox(UUID.randomUUID(), "margin.reserve-requested", "[1,2,3]", CREATED_AT);

        // when, then
        assertThatThrownBy(() -> factory.create(outbox))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JSON 객체가 아닙니다");
    }

    static Outbox outbox(UUID id, String topic, String payload, Instant createdAt) {
        Outbox outbox = Outbox.of("SUBSCRIPTION", UUID.randomUUID(), topic, "key-1", payload);
        ReflectionTestUtils.setField(outbox, "id", id);
        ReflectionTestUtils.setField(outbox, "createdAt", createdAt);
        return outbox;
    }
}
