package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("margin.reserve-failed 메시지를 확보 실패 응답 내용으로 변환 (필수 필드, 형식 검증)")
class MarginReserveFailedMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INSUFFICIENT_BALANCE", "ACCOUNT_NOT_FOUND"})
    @DisplayName("필수 필드가 모두 있고 reason이 허용된 값이면 확보 실패 응답 내용으로 변환하고 reason을 RejectReason으로 바꾼다")
    void toEvent_convertsMessage_whenValid(String reason) {
        // given
        MarginReserveFailedMessage message = message(reason);

        // when
        MarginReserveFailed event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.reason()).isEqualTo(RejectReason.valueOf(reason));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<MarginReserveFailedMessage> breaker) {
        // given
        MarginReserveFailedMessage message = breaker.apply(message("INSUFFICIENT_BALANCE"));

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @ParameterizedTest(name = "reason [{0}]")
    @ValueSource(strings = {"ALREADY_SUBSCRIBED", "UNKNOWN_REASON", "insufficient_balance", " "})
    @DisplayName("허용되지 않은 reason(이중청약, 모르는 값, 소문자)이면 InvalidEventException이 난다")
    void toEvent_throws_whenReasonIsNotAllowed(String reason) {
        // given
        MarginReserveFailedMessage message = message(reason);

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있다")
    void read_ignoresUnknownFields() {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"newField":"x","eventId":"%s","eventType":"margin.reserve-failed","occurredAt":"2026-10-27T15:59:58.850+09:00",
                 "subscriptionId":"%s","reason":"INSUFFICIENT_BALANCE"}""".formatted(EVENT_ID, SUBSCRIPTION_ID);

        // when
        MarginReserveFailed event = MessageSupport.read(objectMapper, json, MarginReserveFailedMessage.class).toEvent();

        // then
        assertThat(event.reason()).isEqualTo(RejectReason.INSUFFICIENT_BALANCE);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                invalid("eventId 없음", m -> new MarginReserveFailedMessage(null, m.eventType(), m.occurredAt(), m.subscriptionId(), m.reason())),
                invalid("eventType 불일치", m -> new MarginReserveFailedMessage(m.eventId(), "margin.reserved", m.occurredAt(), m.subscriptionId(), m.reason())),
                invalid("occurredAt 없음", m -> new MarginReserveFailedMessage(m.eventId(), m.eventType(), null, m.subscriptionId(), m.reason())),
                invalid("occurredAt 형식 오류", m -> new MarginReserveFailedMessage(m.eventId(), m.eventType(), "어제", m.subscriptionId(), m.reason())),
                invalid("subscriptionId 없음", m -> new MarginReserveFailedMessage(m.eventId(), m.eventType(), m.occurredAt(), null, m.reason())),
                invalid("reason 없음", m -> new MarginReserveFailedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), null))
        );
    }

    private static Object[] invalid(String caseName, UnaryOperator<MarginReserveFailedMessage> breaker) {
        return new Object[]{caseName, breaker};
    }

    private static MarginReserveFailedMessage message(String reason) {
        return new MarginReserveFailedMessage(EVENT_ID, "margin.reserve-failed", "2026-10-27T15:59:58.850+09:00",
                SUBSCRIPTION_ID, reason);
    }
}
