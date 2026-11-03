package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.ipo.AllocationFinished;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("allocation.finished 메시지를 배정 완료 내용으로 변환 (필수 필드, 형식 검증)")
class AllocationFinishedMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID IPO_ID = UUID.randomUUID();

    @Test
    @DisplayName("필수 필드가 모두 있으면 배정 완료 내용으로 변환한다")
    void toEvent_convertsMessage_whenValid() {
        // given
        AllocationFinishedMessage message = message(100000L, 1000L);

        // when
        AllocationFinished event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.ipoId()).isEqualTo(IPO_ID);
        assertThat(event.totalAllocatedQuantity()).isEqualTo(100000L);
        assertThat(event.subscriptionCount()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("수량이 0이어도 변환한다 (청약이 없는 공모주)")
    void toEvent_convertsMessage_whenQuantitiesAreZero() {
        // given
        AllocationFinishedMessage message = message(0L, 0L);

        // when
        AllocationFinished event = message.toEvent();

        // then
        assertThat(event.totalAllocatedQuantity()).isZero();
        assertThat(event.subscriptionCount()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리거나 수량이 음수면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<AllocationFinishedMessage> breaker) {
        // given
        AllocationFinishedMessage message = breaker.apply(message(100000L, 1000L));

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있다")
    void read_ignoresUnknownFields() {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"newField":"x","eventId":"%s","eventType":"allocation.finished","occurredAt":"2026-10-27T16:20:30.000+09:00",
                 "ipoId":"%s","totalAllocatedQuantity":100000,"subscriptionCount":1000}""".formatted(EVENT_ID, IPO_ID);

        // when
        AllocationFinished event = MessageSupport.read(objectMapper, json, AllocationFinishedMessage.class).toEvent();

        // then
        assertThat(event.subscriptionCount()).isEqualTo(1000L);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                invalid("eventId 없음", m -> new AllocationFinishedMessage(null, m.eventType(), m.occurredAt(), m.ipoId(), m.totalAllocatedQuantity(), m.subscriptionCount())),
                invalid("eventType 불일치", m -> new AllocationFinishedMessage(m.eventId(), "allocation.result", m.occurredAt(), m.ipoId(), m.totalAllocatedQuantity(), m.subscriptionCount())),
                invalid("occurredAt 없음", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), null, m.ipoId(), m.totalAllocatedQuantity(), m.subscriptionCount())),
                invalid("occurredAt 형식 오류", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), "어제", m.ipoId(), m.totalAllocatedQuantity(), m.subscriptionCount())),
                invalid("ipoId 없음", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), m.occurredAt(), null, m.totalAllocatedQuantity(), m.subscriptionCount())),
                invalid("totalAllocatedQuantity 없음", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), null, m.subscriptionCount())),
                invalid("totalAllocatedQuantity 음수", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), -1L, m.subscriptionCount())),
                invalid("subscriptionCount 없음", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), m.totalAllocatedQuantity(), null)),
                invalid("subscriptionCount 음수", m -> new AllocationFinishedMessage(m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), m.totalAllocatedQuantity(), -1L))
        );
    }

    private static Object[] invalid(String caseName, UnaryOperator<AllocationFinishedMessage> breaker) {
        return new Object[]{caseName, breaker};
    }

    private static AllocationFinishedMessage message(Long totalAllocatedQuantity, Long subscriptionCount) {
        return new AllocationFinishedMessage(EVENT_ID, "allocation.finished", "2026-10-27T16:20:30.000+09:00",
                IPO_ID, totalAllocatedQuantity, subscriptionCount);
    }
}
