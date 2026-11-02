package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.CompensationResult;
import com.gongmoju.subscription.application.subscription.SubscriptionCompensationService;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("margin.reserved 보상 착수 복구 핸들러")
class MarginReservedCompensationRecovererTest {

    private final ConsumerRecordRecoverer deadLetterRecoverer = mock(ConsumerRecordRecoverer.class);
    private final SubscriptionCompensationService compensationService = mock(SubscriptionCompensationService.class);
    private final MarginReservedCompensationRecoverer recoverer =
            new MarginReservedCompensationRecoverer(deadLetterRecoverer, compensationService, new ObjectMapper());

    private final UUID subscriptionId = UUID.randomUUID();

    @Test
    @DisplayName("margin.reserved에서 재시도를 소진한 일시 오류는 보상 착수에 성공하면 DLT로 보내지 않는다")
    void accept_startsCompensationAndSkipsDlt_whenTransientErrorExhausted() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        when(compensationService.startConfirmFailedCompensation(subscriptionId)).thenReturn(CompensationResult.STARTED);

        // when
        recoverer.accept(record, listenerFailure(new IllegalStateException("DB 오류")));

        // then
        verify(compensationService).startConfirmFailedCompensation(subscriptionId);
        verify(deadLetterRecoverer, never()).accept(any(), any());
    }

    @Test
    @DisplayName("이미 PENDING이 아니라서 보상을 시작하지 않아도 정상 처리로 보고 DLT로 보내지 않는다")
    void accept_skipsDlt_whenSubscriptionIsNotPending() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        when(compensationService.startConfirmFailedCompensation(subscriptionId)).thenReturn(CompensationResult.NOT_PENDING_IGNORED);

        // when
        recoverer.accept(record, listenerFailure(new IllegalStateException("DB 오류")));

        // then
        verify(deadLetterRecoverer, never()).accept(any(), any());
    }

    @Test
    @DisplayName("보상 착수에도 실패하면 원래 예외와 함께 DLT로 보낸다")
    void accept_sendsToDlt_whenCompensationFails() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        Exception original = listenerFailure(new IllegalStateException("DB 오류"));
        when(compensationService.startConfirmFailedCompensation(subscriptionId)).thenThrow(new IllegalStateException("보상도 실패"));

        // when
        recoverer.accept(record, original);

        // then
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId라서 보상 착수가 InvalidEventException으로 실패하면 DLT로 보낸다")
    void accept_sendsToDlt_whenSubscriptionNotFound() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        Exception original = listenerFailure(new IllegalStateException("DB 오류"));
        when(compensationService.startConfirmFailedCompensation(subscriptionId)).thenThrow(new InvalidEventException("없음"));

        // when
        recoverer.accept(record, original);

        // then
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("검증 실패(InvalidEventException)는 보상을 시도하지 않고 바로 DLT로 보낸다")
    void accept_sendsToDltWithoutCompensation_whenValidationFailed() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        Exception original = listenerFailure(new InvalidEventException("amount가 1 미만"));

        // when
        recoverer.accept(record, original);

        // then
        verifyNoInteractions(compensationService);
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("IllegalArgumentException도 검증 실패로 보고 보상을 시도하지 않고 바로 DLT로 보낸다")
    void accept_sendsToDltWithoutCompensation_whenIllegalArgument() {
        // given
        ConsumerRecord<String, String> record = reservedRecord(validJson());
        Exception original = listenerFailure(new IllegalArgumentException("잘못된 값"));

        // when
        recoverer.accept(record, original);

        // then
        verifyNoInteractions(compensationService);
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("본문을 읽을 수 없으면 보상 착수 없이 DLT로 보낸다")
    void accept_sendsToDlt_whenPayloadIsBroken() {
        // given
        ConsumerRecord<String, String> record = reservedRecord("{깨진 json");
        Exception original = listenerFailure(new IllegalStateException("DB 오류"));

        // when
        recoverer.accept(record, original);

        // then
        verifyNoInteractions(compensationService);
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("subscriptionId가 없는 본문이면 보상 착수 없이 DLT로 보낸다")
    void accept_sendsToDlt_whenSubscriptionIdIsMissing() {
        // given
        ConsumerRecord<String, String> record = reservedRecord("{\"eventId\":\"" + UUID.randomUUID() + "\"}");
        Exception original = listenerFailure(new IllegalStateException("DB 오류"));

        // when
        recoverer.accept(record, original);

        // then
        verifyNoInteractions(compensationService);
        verify(deadLetterRecoverer).accept(record, original);
    }

    @Test
    @DisplayName("margin.reserved가 아닌 토픽은 보상 착수 없이 DLT로 보낸다")
    void accept_sendsToDlt_whenOtherTopic() {
        // given
        ConsumerRecord<String, String> record = new ConsumerRecord<>("margin.reserve-failed", 0, 0L, "key", validJson());
        Exception original = listenerFailure(new IllegalStateException("DB 오류"));

        // when
        recoverer.accept(record, original);

        // then
        verifyNoInteractions(compensationService);
        verify(deadLetterRecoverer).accept(record, original);
    }

    private ConsumerRecord<String, String> reservedRecord(String payload) {
        return new ConsumerRecord<>(MarginReservedMessage.TOPIC, 0, 0L, "key", payload);
    }

    private Exception listenerFailure(Throwable cause) {
        return new ListenerExecutionFailedException("리스너 실패", cause);
    }

    private String validJson() {
        return """
                {"eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-10-27T15:59:58.905+09:00",
                 "subscriptionId":"%s","marginId":"%s","amount":1500000,"reservedAt":"2026-10-27T15:59:58.900+09:00"}"""
                .formatted(UUID.randomUUID(), subscriptionId, UUID.randomUUID());
    }
}
