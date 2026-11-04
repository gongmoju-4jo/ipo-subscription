package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.AllocationResult;
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

@DisplayName("allocation.result 메시지를 배정 결과로 변환 (필수 필드, 형식 검증)")
class AllocationResultMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID IPO_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();

    @ParameterizedTest(name = "균등 {0}, 비례 {1}, 최종 {2}, 대금 {3}")
    @CsvSource({"1, 2, 3, 45000", "0, 0, 0, 0", "5, 0, 5, 75000"})
    @DisplayName("필수 필드가 모두 있고 균등 + 비례가 최종 배정 수량과 같으면 배정 결과로 변환한다 (0주 배정 포함)")
    void toEvent_convertsMessage_whenValid(long equal, long proportional, long allocated, long amount) {
        // given
        AllocationResultMessage message = message(equal, proportional, allocated, amount);

        // when
        AllocationResult event = message.toEvent();

        // then
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.ipoId()).isEqualTo(IPO_ID);
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.allocatedQuantity()).isEqualTo(allocated);
        assertThat(event.allocatedAmount()).isEqualTo(amount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리거나 균등 + 비례가 최종 배정 수량과 다르면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<AllocationResultMessage> breaker) {
        // given
        AllocationResultMessage message = breaker.apply(message(1, 2, 3, 45_000));

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("allocatedQuantity 필드가 빠진 JSON은 0으로 읽히지 않고 InvalidEventException이 난다")
    void toEvent_throws_whenQuantityIsMissingInJson() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"allocation.result","occurredAt":"2026-10-27T16:20:00.000+09:00",
                 "ipoId":"%s","subscriptionId":"%s","accountId":"%s",
                 "equalAllocatedQuantity":0,"proportionalAllocatedQuantity":0,"allocatedAmount":0}"""
                .formatted(EVENT_ID, IPO_ID, SUBSCRIPTION_ID, ACCOUNT_ID);
        AllocationResultMessage message = objectMapper.readValue(json, AllocationResultMessage.class);

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("모르는 필드가 있는 JSON도 읽을 수 있다")
    void read_ignoresUnknownFields() throws Exception {
        // given
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        String json = """
                {"eventId":"%s","eventType":"allocation.result","occurredAt":"2026-10-27T16:20:00.000+09:00",
                 "ipoId":"%s","subscriptionId":"%s","accountId":"%s","equalAllocatedQuantity":1,
                 "proportionalAllocatedQuantity":2,"allocatedQuantity":3,"allocatedAmount":45000,"newField":"무시"}"""
                .formatted(EVENT_ID, IPO_ID, SUBSCRIPTION_ID, ACCOUNT_ID);

        // when
        AllocationResult event = objectMapper.readValue(json, AllocationResultMessage.class).toEvent();

        // then
        assertThat(event.allocatedQuantity()).isEqualTo(3);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                breaker("eventId 없음", m -> copy(m, null, m.eventType(), m.occurredAt(), m.ipoId(), m.subscriptionId(), m.accountId())),
                breaker("eventType 없음", m -> copy(m, m.eventId(), null, m.occurredAt(), m.ipoId(), m.subscriptionId(), m.accountId())),
                breaker("eventType이 토픽과 다름", m -> copy(m, m.eventId(), "allocation.finished", m.occurredAt(), m.ipoId(), m.subscriptionId(), m.accountId())),
                breaker("occurredAt 없음", m -> copy(m, m.eventId(), m.eventType(), null, m.ipoId(), m.subscriptionId(), m.accountId())),
                breaker("occurredAt이 ISO-8601이 아님", m -> copy(m, m.eventId(), m.eventType(), "어제", m.ipoId(), m.subscriptionId(), m.accountId())),
                breaker("ipoId 없음", m -> copy(m, m.eventId(), m.eventType(), m.occurredAt(), null, m.subscriptionId(), m.accountId())),
                breaker("subscriptionId 없음", m -> copy(m, m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), null, m.accountId())),
                breaker("accountId 없음", m -> copy(m, m.eventId(), m.eventType(), m.occurredAt(), m.ipoId(), m.subscriptionId(), null)),
                breaker("equalAllocatedQuantity 없음", m -> quantities(null, 2L, 3L, 45_000L)),
                breaker("proportionalAllocatedQuantity 없음", m -> quantities(1L, null, 3L, 45_000L)),
                breaker("allocatedQuantity 없음", m -> quantities(1L, 2L, null, 45_000L)),
                breaker("allocatedAmount 없음", m -> quantities(1L, 2L, 3L, null)),
                breaker("equalAllocatedQuantity 음수", m -> quantities(-1L, 4L, 3L, 45_000L)),
                breaker("proportionalAllocatedQuantity 음수", m -> quantities(4L, -1L, 3L, 45_000L)),
                breaker("allocatedQuantity 음수", m -> quantities(0L, 0L, -1L, 45_000L)),
                breaker("allocatedAmount 음수", m -> quantities(1L, 2L, 3L, -1L)),
                breaker("균등 + 비례가 최종보다 작음", m -> quantities(1L, 1L, 3L, 45_000L)),
                breaker("균등 + 비례가 최종보다 큼", m -> quantities(2L, 2L, 3L, 45_000L)),
                breaker("균등 + 비례가 long 범위를 넘음", m -> quantities(Long.MAX_VALUE, 1L, 0L, 0L))
        );
    }

    private static Object[] breaker(String name, UnaryOperator<AllocationResultMessage> operator) {
        return new Object[]{name, operator};
    }

    private static AllocationResultMessage copy(AllocationResultMessage m, UUID eventId, String eventType, String occurredAt,
                                                UUID ipoId, UUID subscriptionId, UUID accountId) {
        return new AllocationResultMessage(eventId, eventType, occurredAt, ipoId, subscriptionId, accountId,
                m.equalAllocatedQuantity(), m.proportionalAllocatedQuantity(), m.allocatedQuantity(), m.allocatedAmount());
    }

    private static AllocationResultMessage quantities(Long equal, Long proportional, Long allocated, Long amount) {
        return new AllocationResultMessage(EVENT_ID, "allocation.result", "2026-10-27T16:20:00.000+09:00",
                IPO_ID, SUBSCRIPTION_ID, ACCOUNT_ID, equal, proportional, allocated, amount);
    }

    private static AllocationResultMessage message(long equal, long proportional, long allocated, long amount) {
        return quantities(equal, proportional, allocated, amount);
    }
}
