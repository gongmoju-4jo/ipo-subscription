package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.AllocateResult;
import com.gongmoju.subscription.application.subscription.SubscriptionAllocateService;
import com.gongmoju.subscription.domain.subscription.AllocationResult;
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

@DisplayName("allocation.result Consumer (변환과 예외 전달)")
class AllocationResultConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionAllocateService allocateService = mock(SubscriptionAllocateService.class);
    private final AllocationResultConsumer consumer = new AllocationResultConsumer(objectMapper, allocateService);

    private final UUID eventId = UUID.randomUUID();
    private final UUID ipoId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 배정 결과로 변환해서 배정 처리를 호출한다")
    void consume_callsAllocateService_whenPayloadIsValid() {
        // given
        when(allocateService.allocate(any())).thenReturn(AllocateResult.ALLOCATED);

        // when
        consumer.consume(validJson(1, 2, 3, 45_000));

        // then
        ArgumentCaptor<AllocationResult> captor = ArgumentCaptor.forClass(AllocationResult.class);
        verify(allocateService).allocate(captor.capture());
        AllocationResult event = captor.getValue();
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(event.ipoId()).isEqualTo(ipoId);
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.allocatedQuantity()).isEqualTo(3);
        assertThat(event.allocatedAmount()).isEqualTo(45_000);
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{깨진 json", "null", "[]", "\"문자열\""})
    @DisplayName("본문이 비었거나 JSON 객체가 아니면 InvalidEventException이 나고 배정 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenPayloadIsBroken(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(allocateService, never()).allocate(any());
    }

    @Test
    @DisplayName("균등 + 비례가 최종 배정 수량과 다르면 InvalidEventException이 나고 배정 처리를 호출하지 않는다")
    void consume_throwsInvalidEvent_whenQuantitiesDoNotAddUp() {
        // given
        String payload = validJson(1, 1, 3, 45_000);

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(allocateService, never()).allocate(any());
    }

    @Test
    @DisplayName("배정 처리에서 난 예외는 삼키지 않고 그대로 던진다 (재시도와 DLT는 에러 핸들러가 담당)")
    void consume_rethrowsException_whenAllocateFails() {
        // given
        when(allocateService.allocate(any())).thenThrow(new InvalidEventException("존재하지 않는 subscriptionId입니다"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson(0, 0, 0, 0))).isInstanceOf(InvalidEventException.class);
    }

    private String validJson(long equal, long proportional, long allocated, long amount) {
        return """
                {"eventId":"%s","eventType":"allocation.result","occurredAt":"2026-10-27T16:20:00.000+09:00",
                 "ipoId":"%s","subscriptionId":"%s","accountId":"%s","equalAllocatedQuantity":%d,
                 "proportionalAllocatedQuantity":%d,"allocatedQuantity":%d,"allocatedAmount":%d}"""
                .formatted(eventId, ipoId, subscriptionId, accountId, equal, proportional, allocated, amount);
    }
}
