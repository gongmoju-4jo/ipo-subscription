package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("margin.settled 메시지를 정산 결과로 변환 (필수 필드, 형식 검증)")
class MarginSettledMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final String OCCURRED_AT = "2026-10-27T16:20:40.000+09:00";

    @ParameterizedTest(name = "정산 금액 {0}, 환불액 {1}")
    @CsvSource({"45000, 14955000", "0, 1500000", "1500000, 0"})
    @DisplayName("필수 필드가 모두 있고 금액이 0 이상이면 정산 결과로 변환한다 (0주 배정, 전부 배정 포함)")
    void toEvent_convertsMessage_whenValid(long posted, long refund) {
        // given
        MarginSettledMessage message = amounts(posted, refund);

        // when
        MarginSettled event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.postedAmount()).isEqualTo(posted);
        assertThat(event.refundAmount()).isEqualTo(refund);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리거나 금액이 음수면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<MarginSettledMessage> breaker) {
        // given
        MarginSettledMessage message = breaker.apply(amounts(45_000L, 14_955_000L));

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("postedAmount 필드가 빠진 JSON은 0으로 읽히지 않고 InvalidEventException이 난다")
    void toEvent_throws_whenPostedAmountIsMissingInJson() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"margin.settled","occurredAt":"%s",
                 "subscriptionId":"%s","refundAmount":1500000}"""
                .formatted(EVENT_ID, OCCURRED_AT, SUBSCRIPTION_ID);
        MarginSettledMessage message = objectMapper.readValue(json, MarginSettledMessage.class);

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있다")
    void read_ignoresUnknownFields() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"margin.settled","occurredAt":"%s",
                 "subscriptionId":"%s","postedAmount":45000,"refundAmount":14955000,"newField":"무시"}"""
                .formatted(EVENT_ID, OCCURRED_AT, SUBSCRIPTION_ID);

        // when
        MarginSettled event = objectMapper.readValue(json, MarginSettledMessage.class).toEvent();

        // then
        assertThat(event.postedAmount()).isEqualTo(45_000);
        assertThat(event.refundAmount()).isEqualTo(14_955_000);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                breaker("eventId 없음", m -> new MarginSettledMessage(null, m.eventType(), m.occurredAt(), m.subscriptionId(), m.postedAmount(), m.refundAmount())),
                breaker("eventType 없음", m -> new MarginSettledMessage(m.eventId(), null, m.occurredAt(), m.subscriptionId(), m.postedAmount(), m.refundAmount())),
                breaker("eventType이 토픽과 다름", m -> new MarginSettledMessage(m.eventId(), "margin.voided", m.occurredAt(), m.subscriptionId(), m.postedAmount(), m.refundAmount())),
                breaker("occurredAt 없음", m -> new MarginSettledMessage(m.eventId(), m.eventType(), null, m.subscriptionId(), m.postedAmount(), m.refundAmount())),
                breaker("occurredAt이 ISO-8601이 아님", m -> new MarginSettledMessage(m.eventId(), m.eventType(), "어제", m.subscriptionId(), m.postedAmount(), m.refundAmount())),
                breaker("subscriptionId 없음", m -> new MarginSettledMessage(m.eventId(), m.eventType(), m.occurredAt(), null, m.postedAmount(), m.refundAmount())),
                breaker("postedAmount 없음", m -> amounts(null, 14_955_000L)),
                breaker("refundAmount 없음", m -> amounts(45_000L, null)),
                breaker("postedAmount 음수", m -> amounts(-1L, 14_955_000L)),
                breaker("refundAmount 음수", m -> amounts(45_000L, -1L))
        );
    }

    private static Object[] breaker(String name, UnaryOperator<MarginSettledMessage> operator) {
        return new Object[]{name, operator};
    }

    private static MarginSettledMessage amounts(Long posted, Long refund) {
        return new MarginSettledMessage(EVENT_ID, "margin.settled", OCCURRED_AT, SUBSCRIPTION_ID, posted, refund);
    }
}
