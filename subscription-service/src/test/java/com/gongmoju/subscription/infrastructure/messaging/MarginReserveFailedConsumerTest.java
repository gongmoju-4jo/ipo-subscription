package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.ReserveFailedResult;
import com.gongmoju.subscription.application.subscription.SubscriptionRejectService;
import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.RejectReason;
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

@DisplayName("margin.reserve-failed Consumer (변환과 예외 전달)")
class MarginReserveFailedConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionRejectService rejectService = mock(SubscriptionRejectService.class);
    private final MarginReserveFailedConsumer consumer = new MarginReserveFailedConsumer(objectMapper, rejectService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 확보 실패 응답 내용으로 변환해서 거절 처리를 호출한다")
    void consume_callsRejectService_whenPayloadIsValid() {
        // given
        when(rejectService.reject(any())).thenReturn(ReserveFailedResult.REJECTED);

        // when
        consumer.consume(validJson("INSUFFICIENT_BALANCE"));

        // then
        ArgumentCaptor<MarginReserveFailed> captor = ArgumentCaptor.forClass(MarginReserveFailed.class);
        verify(rejectService).reject(captor.capture());
        MarginReserveFailed event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(event.reason()).isEqualTo(RejectReason.INSUFFICIENT_BALANCE);
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{깨진 json", "null", "[]", "\"문자열\""})
    @DisplayName("본문이 비었거나 JSON 객체가 아니면 InvalidEventException이 나고 거절 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenPayloadIsBroken(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(rejectService, never()).reject(any());
    }

    @Test
    @DisplayName("알 수 없는 reason이면 InvalidEventException이 나고 거절 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenReasonIsUnknown() {
        // given
        String payload = validJson("UNKNOWN_REASON");

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(rejectService, never()).reject(any());
    }

    @Test
    @DisplayName("거절 처리에서 난 예외는 삼키지 않고 그대로 던진다 (재시도와 DLT는 에러 핸들러가 담당)")
    void consume_rethrowsException_whenRejectFails() {
        // given
        when(rejectService.reject(any())).thenThrow(new InvalidEventException("존재하지 않는 subscriptionId입니다"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson("ACCOUNT_NOT_FOUND"))).isInstanceOf(InvalidEventException.class);
    }

    private String validJson(String reason) {
        return """
                {"eventId":"%s","eventType":"margin.reserve-failed","occurredAt":"2026-10-27T15:59:58.850+09:00",
                 "subscriptionId":"%s","reason":"%s"}""".formatted(eventId, subscriptionId, reason);
    }
}
