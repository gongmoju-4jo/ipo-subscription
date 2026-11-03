package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.SubscriptionVoidedService;
import com.gongmoju.subscription.application.subscription.VoidedResult;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("margin.voided Consumer (변환과 예외 전달)")
class MarginVoidedConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionVoidedService voidedService = mock(SubscriptionVoidedService.class);
    private final MarginVoidedConsumer consumer = new MarginVoidedConsumer(objectMapper, voidedService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 해제 완료 응답 내용으로 변환해서 취소 완료 처리를 호출한다")
    void consume_callsVoidedService_whenPayloadIsValid() {
        // given
        when(voidedService.complete(any())).thenReturn(VoidedResult.CANCELLED);

        // when
        consumer.consume(validJson(15_000_000));

        // then
        ArgumentCaptor<MarginVoided> captor = ArgumentCaptor.forClass(MarginVoided.class);
        verify(voidedService).complete(captor.capture());
        MarginVoided event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(event.amount()).isEqualTo(15_000_000);
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{깨진 json", "null", "[]", "\"문자열\""})
    @DisplayName("본문이 비었거나 JSON 객체가 아니면 InvalidEventException이 나고 취소 완료 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenPayloadIsBroken(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(voidedService, never()).complete(any());
    }

    @Test
    @DisplayName("amount가 음수면 InvalidEventException이 나고 취소 완료 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenAmountIsNegative() {
        // given
        String payload = validJson(-1);

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(voidedService, never()).complete(any());
    }

    @Test
    @DisplayName("취소 완료 처리에서 난 예외는 삼키지 않고 그대로 던진다 (재시도와 DLT는 에러 핸들러가 담당)")
    void consume_rethrowsException_whenCompleteFails() {
        // given
        when(voidedService.complete(any())).thenThrow(new InvalidEventException("존재하지 않는 subscriptionId입니다"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson(0))).isInstanceOf(InvalidEventException.class);
    }

    private String validJson(long amount) {
        return """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-10-27T16:00:29.350+09:00",
                 "subscriptionId":"%s","amount":%d}""".formatted(eventId, subscriptionId, amount);
    }
}
