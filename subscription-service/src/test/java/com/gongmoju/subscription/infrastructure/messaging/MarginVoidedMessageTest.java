package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
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

@DisplayName("margin.voided 메시지를 해제 완료 응답 내용으로 변환 (필수 필드, 형식 검증)")
class MarginVoidedMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();

    @ParameterizedTest(name = "amount {0}")
    @ValueSource(longs = {0, 1, 15_000_000})
    @DisplayName("필수 필드가 모두 있고 amount가 0 이상이면 해제 완료 응답 내용으로 변환한다 (확보한 증거금이 없던 amount 0도 허용)")
    void toEvent_convertsMessage_whenValid(long amount) {
        // given
        MarginVoidedMessage message = message(amount);

        // when
        MarginVoided event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.amount()).isEqualTo(amount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<MarginVoidedMessage> breaker) {
        // given
        MarginVoidedMessage message = breaker.apply(message(15_000_000));

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("amount 필드가 빠진 JSON은 0으로 읽히지 않고 InvalidEventException이 난다")
    void toEvent_throws_whenAmountIsMissingInJson() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-10-27T16:00:29.350+09:00",
                 "subscriptionId":"%s"}""".formatted(EVENT_ID, SUBSCRIPTION_ID);
        MarginVoidedMessage message = objectMapper.readValue(json, MarginVoidedMessage.class);

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있다")
    void read_ignoresUnknownFields() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-10-27T16:00:29.350+09:00",
                 "subscriptionId":"%s","amount":15000000,"newField":"무시"}""".formatted(EVENT_ID, SUBSCRIPTION_ID);

        // when
        MarginVoided event = objectMapper.readValue(json, MarginVoidedMessage.class).toEvent();

        // then
        assertThat(event.amount()).isEqualTo(15_000_000);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                breaker("eventId 없음", m -> new MarginVoidedMessage(null, m.eventType(), m.occurredAt(), m.subscriptionId(), m.amount())),
                breaker("eventType 없음", m -> new MarginVoidedMessage(m.eventId(), null, m.occurredAt(), m.subscriptionId(), m.amount())),
                breaker("eventType이 토픽과 다름", m -> new MarginVoidedMessage(m.eventId(), "margin.reserved", m.occurredAt(), m.subscriptionId(), m.amount())),
                breaker("occurredAt 없음", m -> new MarginVoidedMessage(m.eventId(), m.eventType(), null, m.subscriptionId(), m.amount())),
                breaker("occurredAt이 ISO-8601이 아님", m -> new MarginVoidedMessage(m.eventId(), m.eventType(), "어제", m.subscriptionId(), m.amount())),
                breaker("subscriptionId 없음", m -> new MarginVoidedMessage(m.eventId(), m.eventType(), m.occurredAt(), null, m.amount())),
                breaker("amount 없음", m -> new MarginVoidedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), null)),
                breaker("amount 음수", m -> new MarginVoidedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.subscriptionId(), -1L))
        );
    }

    private static Object[] breaker(String name, UnaryOperator<MarginVoidedMessage> operator) {
        return new Object[]{name, operator};
    }

    private MarginVoidedMessage message(long amount) {
        return new MarginVoidedMessage(EVENT_ID, "margin.voided", "2026-10-27T16:00:29.350+09:00", SUBSCRIPTION_ID, amount);
    }
}
