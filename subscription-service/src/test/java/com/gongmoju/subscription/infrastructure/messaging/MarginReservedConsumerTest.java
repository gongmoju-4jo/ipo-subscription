package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.ConfirmResult;
import com.gongmoju.subscription.application.subscription.SubscriptionConfirmService;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("margin.reserved Consumer (변환과 예외 전달)")
class MarginReservedConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionConfirmService confirmService = mock(SubscriptionConfirmService.class);
    private final MarginReservedConsumer consumer = new MarginReservedConsumer(objectMapper, confirmService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID marginId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 확보 성공 응답 내용으로 변환해서 확정 처리를 호출한다")
    void consume_callsConfirmService_whenPayloadIsValid() {
        // given
        when(confirmService.confirm(any())).thenReturn(ConfirmResult.CONFIRMED);

        // when
        consumer.consume(validJson());

        // then
        ArgumentCaptor<MarginReserved> captor = ArgumentCaptor.forClass(MarginReserved.class);
        verify(confirmService).confirm(captor.capture());
        MarginReserved event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(event.marginId()).isEqualTo(marginId);
        assertThat(event.amount()).isEqualTo(15_000_000);
        assertThat(event.reservedAt()).isEqualTo(Instant.parse("2026-10-27T06:59:58.900Z"));
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{깨진 json", "null", "[]", "\"문자열\""})
    @DisplayName("본문이 비었거나 JSON 객체가 아니면 InvalidEventException이 나고 확정 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenPayloadIsBroken(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(confirmService, never()).confirm(any());
    }

    @Test
    @DisplayName("필수 필드가 없으면 InvalidEventException이 나고 확정 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenRequiredFieldIsMissing() {
        // given
        String withoutMarginId = validJson().replaceFirst("\"marginId\":\"[^\"]*\",", "");

        // when, then
        assertThatThrownBy(() -> consumer.consume(withoutMarginId)).isInstanceOf(InvalidEventException.class);
        verify(confirmService, never()).confirm(any());
    }

    @Test
    @DisplayName("확정 처리에서 난 예외는 삼키지 않고 그대로 던진다 (재시도와 DLT는 에러 핸들러가 담당)")
    void consume_rethrowsException_whenConfirmFails() {
        // given
        when(confirmService.confirm(any())).thenThrow(new InvalidEventException("존재하지 않는 subscriptionId입니다"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson())).isInstanceOf(InvalidEventException.class);
    }

    private String validJson() {
        return """
                {"eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-10-27T15:59:58.905+09:00",
                 "subscriptionId":"%s","marginId":"%s","amount":15000000,"reservedAt":"2026-10-27T15:59:58.900+09:00"}"""
                .formatted(eventId, subscriptionId, marginId);
    }
}
