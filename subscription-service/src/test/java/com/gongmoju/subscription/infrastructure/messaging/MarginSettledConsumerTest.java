package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.RefundResult;
import com.gongmoju.subscription.application.subscription.SubscriptionRefundService;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
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

@DisplayName("margin.settled Consumer (변환과 예외 전달)")
class MarginSettledConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionRefundService refundService = mock(SubscriptionRefundService.class);
    private final MarginSettledConsumer consumer = new MarginSettledConsumer(objectMapper, refundService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 정산 결과로 변환해서 환불 처리를 호출한다")
    void consume_callsRefundService_whenPayloadIsValid() {
        // given
        when(refundService.refund(any())).thenReturn(RefundResult.REFUNDED);

        // when
        consumer.consume(validJson(45_000, 14_955_000));

        // then
        ArgumentCaptor<MarginSettled> captor = ArgumentCaptor.forClass(MarginSettled.class);
        verify(refundService).refund(captor.capture());
        MarginSettled event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(event.postedAmount()).isEqualTo(45_000);
        assertThat(event.refundAmount()).isEqualTo(14_955_000);
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{깨진 json", "null", "[]", "\"문자열\""})
    @DisplayName("본문이 비었거나 JSON 객체가 아니면 InvalidEventException이 나고 환불 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenPayloadIsBroken(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(refundService, never()).refund(any());
    }

    @Test
    @DisplayName("금액이 음수면 InvalidEventException이 나고 환불 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenAmountIsNegative() {
        // given
        String payload = validJson(-1, 1_500_001);

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(refundService, never()).refund(any());
    }

    @Test
    @DisplayName("환불 처리에서 난 예외는 삼키지 않고 그대로 던진다 (재시도와 DLT는 에러 핸들러가 담당)")
    void consume_rethrowsException_whenRefundFails() {
        // given
        when(refundService.refund(any())).thenThrow(new InvalidEventException("존재하지 않는 subscriptionId입니다"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson(0, 1_500_000))).isInstanceOf(InvalidEventException.class);
    }

    private String validJson(long posted, long refund) {
        return """
                {"eventId":"%s","eventType":"margin.settled","occurredAt":"2026-10-27T16:20:40.000+09:00",
                 "subscriptionId":"%s","postedAmount":%d,"refundAmount":%d}"""
                .formatted(eventId, subscriptionId, posted, refund);
    }
}
