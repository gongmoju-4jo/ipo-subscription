package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("margin.reserved 메시지를 확보 성공 응답 내용으로 변환 (필수 필드, 형식 검증)")
class MarginReservedMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID MARGIN_ID = UUID.randomUUID();

    @Test
    @DisplayName("필수 필드가 모두 있고 형식이 맞으면 확보 성공 응답 내용으로 변환하고, +09:00 시각을 Instant로 바꾼다")
    void toEvent_convertsMessage_whenValid() {
        // given
        MarginReservedMessage message = valid();

        // when
        MarginReserved event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.marginId()).isEqualTo(MARGIN_ID);
        assertThat(event.amount()).isEqualTo(15_000_000);
        assertThat(event.reservedAt()).isEqualTo(Instant.parse("2026-10-27T06:59:58.900Z"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리거나 금액이 1보다 작으면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<MarginReservedMessage> breaker) {
        // given
        MarginReservedMessage message = breaker.apply(valid());

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있고 금액이 없으면 0으로 읽혀 검증에서 InvalidEventException이 난다")
    void read_ignoresUnknownFields_andMissingAmountIsRejected() {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"newField":"x","eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-10-27T15:59:58.905+09:00",
                 "subscriptionId":"%s","marginId":"%s","reservedAt":"2026-10-27T15:59:58.900+09:00"}"""
                .formatted(EVENT_ID, SUBSCRIPTION_ID, MARGIN_ID);

        // when
        MarginReservedMessage message = MessageSupport.read(objectMapper, json, MarginReservedMessage.class);

        // then
        assertThat(message.amount()).isZero();
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                invalid("eventId 없음", m -> new MarginReservedMessage(null, m.eventType(), m.occurredAt(), m.subscriptionId(), m.marginId(), m.amount(), m.reservedAt())),
                invalid("eventType 불일치", m -> new MarginReservedMessage(m.eventId(), "margin.reserve-failed", m.occurredAt(), m.subscriptionId(), m.marginId(), m.amount(), m.reservedAt())),
                invalid("occurredAt 없음", m -> new MarginReservedMessage(m.eventId(), m.eventType(), null, m.subscriptionId(), m.marginId(), m.amount(), m.reservedAt())),
                invalid("occurredAt 형식 오류", m -> new MarginReservedMessage(m.eventId(), m.eventType(), "어제", m.subscriptionId(), m.marginId(), m.amount(), m.reservedAt())),
                invalid("subscriptionId 없음", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), null, m.marginId(), m.amount(), m.reservedAt())),
                invalid("marginId 없음", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), null, m.amount(), m.reservedAt())),
                invalid("amount 0", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), m.marginId(), 0, m.reservedAt())),
                invalid("amount 음수", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), m.marginId(), -1, m.reservedAt())),
                invalid("reservedAt 없음", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), m.marginId(), m.amount(), null)),
                invalid("reservedAt 형식 오류", m -> new MarginReservedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), m.marginId(), m.amount(), "2026-10-27"))
        );
    }

    private static Object[] invalid(String caseName, UnaryOperator<MarginReservedMessage> breaker) {
        return new Object[]{caseName, breaker};
    }

    private static MarginReservedMessage valid() {
        return new MarginReservedMessage(EVENT_ID, "margin.reserved", "2026-10-27T15:59:58.905+09:00",
                SUBSCRIPTION_ID, MARGIN_ID, 15_000_000, "2026-10-27T15:59:58.900+09:00");
    }
}
