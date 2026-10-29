package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("OutboxPublisher: 미발행 Outbox를 Kafka로 발행")
class OutboxPublisherTest {

    private static final int BATCH_SIZE = 100;
    private static final int ALERT_THRESHOLD = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OutboxJpaRepository outboxRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxRepository = mock(OutboxJpaRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        publisher = newPublisher(Duration.ofSeconds(1));
    }

    private OutboxPublisher newPublisher(Duration sendTimeout) {
        return new OutboxPublisher(
                outboxRepository,
                new OutboxMessageFactory(objectMapper),
                kafkaTemplate,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new OutboxPublisherProperties(BATCH_SIZE, ALERT_THRESHOLD, sendTimeout));
    }

    @Test
    @DisplayName("미발행 행이 없으면 Kafka로 아무것도 보내지 않는다")
    void publishPending_doesNothingWhenNoPendingRows() {
        // given
        givenPending(List.of());

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("batchSize만큼만, 미발행 행을 조회한다")
    void publishPending_queriesPendingRowsWithBatchSize() {
        // given
        givenPending(List.of());

        // when
        publisher.publishPending();

        // then
        verify(outboxRepository).findByPublishedAtIsNullOrderByCreatedAtAsc(PageRequest.ofSize(BATCH_SIZE));
    }

    @Test
    @DisplayName("정상 발행하면 토픽, 키, 메시지로 보내고 publishedAt을 기록한다")
    void publishPending_marksPublishedAfterSuccessfulSend() throws Exception {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{\"accountId\":\"a-1\"}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("margin.reserved"), eq("key-1"), message.capture());
        JsonNode body = objectMapper.readTree(message.getValue());
        assertThat(body.get("eventId").asText()).isEqualTo(outbox.getId().toString());
        assertThat(body.get("accountId").asText()).isEqualTo("a-1");
        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("Kafka 발행이 실패하면 retryCount를 올리고 publishedAt은 비워 둔다")
    void publishPending_increasesRetryCountWhenSendFails() {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        givenSendFails();

        // when
        publisher.publishPending();

        // then
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("send 호출 자체가 예외를 던져도 retryCount를 올리고 다음 행으로 넘어간다")
    void publishPending_handlesExceptionThrownBySend() {
        // given
        Outbox failing = pendingOutbox("margin.reserved", "key-1", "{}");
        Outbox next = pendingOutbox("margin.reserved", "key-2", "{}");
        givenPending(List.of(failing, next));
        givenFound(failing, next);
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenThrow(new IllegalStateException("producer closed"));
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        assertThat(failing.getRetryCount()).isEqualTo(1);
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(next.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("발행 성공 응답이 시간 안에 오지 않으면 실패로 보고 retryCount를 올린다")
    void publishPending_treatsSendTimeoutAsFailure() {
        // given
        publisher = newPublisher(Duration.ofMillis(50));
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        // when
        publisher.publishPending();

        // then
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 인터럽트 상태를 되살리고 retryCount를 올린다")
    @SuppressWarnings("unchecked")
    void publishPending_restoresInterruptFlagWhenInterrupted() throws Exception {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new InterruptedException());
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(future);

        try {
            // when
            publisher.publishPending();

            // then
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(outbox.getRetryCount()).isEqualTo(1);
            assertThat(outbox.getPublishedAt()).isNull();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("인터럽트되면 남은 행은 시도하지 않아서 retryCount가 오르지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_stopsRemainingRowsWhenInterrupted() throws Exception {
        // given
        Outbox interrupted = pendingOutbox("margin.reserved", "key-1", "{}");
        Outbox remaining = pendingOutbox("margin.reserved", "key-2", "{}");
        givenPending(List.of(interrupted, remaining));
        givenFound(interrupted, remaining);
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new InterruptedException());
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(future);
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        try {
            // when
            publisher.publishPending();

            // then
            verify(kafkaTemplate, never()).send(anyString(), eq("key-2"), anyString());
            assertThat(remaining.getRetryCount()).isZero();
            assertThat(remaining.getPublishedAt()).isNull();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("payload를 읽을 수 없는 행은 Kafka로 보내지 않고 retryCount를 올린다")
    void publishPending_recordsFailureWhenMessageCannotBeCreated() {
        // given
        Outbox broken = pendingOutbox("margin.reserved", "key-1", "{not-json");
        givenPending(List.of(broken));
        givenFound(broken);

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(broken.getRetryCount()).isEqualTo(1);
        assertThat(broken.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("같은 키의 행이 실패하면 이번 실행에서는 같은 키의 뒤 행을 보내지 않고, 다른 키는 보낸다")
    void publishPending_blocksLaterRowsOfSameKeyAfterFailure() {
        // given
        Outbox first = pendingOutbox("margin.reserved", "key-1", "{}");
        Outbox sameKey = pendingOutbox("margin.reserved", "key-1", "{}");
        Outbox otherKey = pendingOutbox("margin.reserved", "key-2", "{}");
        givenPending(List.of(first, sameKey, otherKey));
        givenFound(first, sameKey, otherKey);
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(failedFuture());
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, times(1)).send(anyString(), eq("key-1"), anyString());
        verify(kafkaTemplate, times(1)).send(anyString(), eq("key-2"), anyString());
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(sameKey.getRetryCount()).isZero();
        assertThat(sameKey.getPublishedAt()).isNull();
        assertThat(otherKey.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 키의 앞 행이 성공하면 뒤 행도 만든 순서대로 이어서 발행한다")
    void publishPending_publishesRowsOfSameKeyInOrderWhenSuccessful() {
        // given
        Outbox first = pendingOutbox("margin.reserved", "key-1", "{\"seq\":1}");
        Outbox second = pendingOutbox("margin.reserved", "key-1", "{\"seq\":2}");
        givenPending(List.of(first, second));
        givenFound(first, second);
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("margin.reserved"), eq("key-1"), messages.capture());
        assertThat(messages.getAllValues().get(0)).contains("\"seq\":1");
        assertThat(messages.getAllValues().get(1)).contains("\"seq\":2");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("발행 후 완료 표시에 실패해도 예외를 밖으로 던지지 않고, 같은 키의 뒤 행은 보내지 않는다")
    void publishPending_swallowsMarkPublishedFailureAndBlocksKey() {
        // given
        Outbox first = pendingOutbox("margin.reserved", "key-1", "{}");
        Outbox sameKey = pendingOutbox("margin.reserved", "key-1", "{}");
        givenPending(List.of(first, sameKey));
        when(outboxRepository.findById(first.getId())).thenThrow(new IllegalStateException("db down"));
        givenSendSucceeds();

        // when, then
        assertThatCode(() -> publisher.publishPending()).doesNotThrowAnyException();
        verify(kafkaTemplate, times(1)).send(anyString(), eq("key-1"), anyString());
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getRetryCount()).isZero();
        assertThat(sameKey.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("완료 표시에 실패한 행은 다음 실행에서 같은 eventId, occurredAt으로 다시 발행된다")
    void publishPending_republishesWithSameEventIdAndOccurredAt() throws Exception {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{\"accountId\":\"a-1\"}");
        givenPending(List.of(outbox));
        when(outboxRepository.findById(outbox.getId()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Optional.of(outbox));
        givenSendSucceeds();

        // when
        publisher.publishPending();
        publisher.publishPending();

        // then
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("margin.reserved"), eq("key-1"), messages.capture());
        JsonNode firstSend = objectMapper.readTree(messages.getAllValues().get(0));
        JsonNode secondSend = objectMapper.readTree(messages.getAllValues().get(1));
        assertThat(secondSend.get("eventId")).isEqualTo(firstSend.get("eventId"));
        assertThat(secondSend.get("occurredAt")).isEqualTo(firstSend.get("occurredAt"));
        assertThat(outbox.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("실패 횟수가 알림 기준 미만이면 WARN 로그만 남긴다")
    void publishPending_logsWarnBelowAlertThreshold(CapturedOutput output) {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        givenSendFails();

        // when
        publisher.publishPending();

        // then
        assertThat(output.getAll()).contains("Outbox 발행 실패 (다음 실행에서 재시도)");
        assertThat(output.getAll()).doesNotContain("Outbox 발행 연속 실패");
    }

    @Test
    @DisplayName("실패 횟수가 알림 기준 이상이면 연속 실패 ERROR 로그를 남기고 발행은 계속 시도한다")
    void publishPending_logsAlertAtThreshold(CapturedOutput output) {
        // given
        Outbox outbox = pendingOutbox("margin.reserved", "key-1", "{}");
        for (int i = 0; i < ALERT_THRESHOLD - 1; i++) {
            outbox.increaseRetryCount();
        }
        givenPending(List.of(outbox));
        givenFound(outbox);
        givenSendFails();

        // when
        publisher.publishPending();
        publisher.publishPending();

        // then
        assertThat(outbox.getRetryCount()).isEqualTo(ALERT_THRESHOLD + 1);
        assertThat(output.getAll()).contains("Outbox 발행 연속 실패");
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    private void givenPending(List<Outbox> outboxes) {
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any())).thenReturn(outboxes);
    }

    private void givenFound(Outbox... outboxes) {
        for (Outbox outbox : outboxes) {
            when(outboxRepository.findById(outbox.getId())).thenReturn(Optional.of(outbox));
        }
    }

    private void givenSendSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void givenSendFails() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedFuture());
    }

    private CompletableFuture<SendResult<String, String>> failedFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"));
    }

    private Outbox pendingOutbox(String topic, String messageKey, String payload) {
        Outbox outbox = Outbox.of("MARGIN", UUID.randomUUID(), topic, messageKey, payload);
        ReflectionTestUtils.setField(outbox, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(outbox, "createdAt", Instant.parse("2026-10-27T06:59:58.123Z"));
        return outbox;
    }
}
