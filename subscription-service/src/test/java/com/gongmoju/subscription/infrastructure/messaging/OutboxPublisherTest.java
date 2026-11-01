package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.stubbing.Answer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
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
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{\"accountId\":\"a-1\"}");
        givenPending(List.of(outbox));
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("margin.reserve-requested"), eq("key-1"), message.capture());
        JsonNode body = objectMapper.readTree(message.getValue());
        assertThat(body.get("eventId").asText()).isEqualTo(outbox.getId().toString());
        assertThat(body.get("accountId").asText()).isEqualTo("a-1");
        assertThat(outbox.getPublishedAt()).isNotNull();
        assertThat(outbox.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("키가 다른 행들은 응답을 기다리지 않고 모두 보낸 뒤, 한 번의 UPDATE로 발행 완료를 표시한다")
    void publishPending_sendsAllKeysBeforeMarkingPublishedInOneUpdate() {
        // given
        Outbox first = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox second = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        Outbox third = pendingOutbox("margin.reserve-requested", "key-3", "{}");
        givenPending(List.of(first, second, third));
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        InOrder inOrder = inOrder(kafkaTemplate, outboxRepository);
        inOrder.verify(kafkaTemplate, times(3)).send(anyString(), anyString(), anyString());
        inOrder.verify(outboxRepository).markPublished(any(), anyCollection());
        List<Collection<UUID>> batches = markedIdBatches(1);
        assertThat(batches.get(0)).containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
    }

    @Test
    @DisplayName("한 번의 반복에서는 같은 키의 가장 오래된 행 하나만 보내고, 다음 행은 바로 다음 반복에서 보낸다")
    void publishPending_sendsOnlyOldestRowPerKeyInEachBatch() {
        // given
        Outbox firstOfKey1 = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox secondOfKey1 = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox onlyOfKey2 = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(firstOfKey1, secondOfKey1, onlyOfKey2));
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        List<Collection<UUID>> batches = markedIdBatches(2);
        assertThat(batches.get(0)).containsExactlyInAnyOrder(firstOfKey1.getId(), onlyOfKey2.getId());
        assertThat(batches.get(1)).containsExactly(secondOfKey1.getId());
        assertThat(secondOfKey1.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 키의 행은 만든 순서대로 이어서 발행한다")
    void publishPending_publishesRowsOfSameKeyInOrderWhenSuccessful() {
        // given
        Outbox first = pendingOutbox("margin.reserve-requested", "key-1", "{\"seq\":1}");
        Outbox second = pendingOutbox("margin.reserve-requested", "key-1", "{\"seq\":2}");
        givenPending(List.of(first, second));
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("margin.reserve-requested"), eq("key-1"), messages.capture());
        assertThat(messages.getAllValues().get(0)).contains("\"seq\":1");
        assertThat(messages.getAllValues().get(1)).contains("\"seq\":2");
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("성공이 있으면 쉬지 않고 다음 반복을 하고, 더 보낼 행이 없으면 끝낸다")
    void publishPending_keepsGoingWhileRowsArePublished() {
        // given
        Outbox first = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox second = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        givenPending(List.of(first, second));
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        verify(outboxRepository, times(3)).findByPublishedAtIsNullOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("일부만 성공하면 성공한 행만 발행 완료로 표시하고, 실패한 행은 retryCount를 올린다")
    void publishPending_marksOnlyConfirmedRowsWhenSomeFail() {
        // given
        Outbox failing = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox succeeding = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(failing, succeeding));
        givenFound(failing, succeeding);
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(failedFuture());
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        assertThat(markedIdBatches(1).get(0)).containsExactly(succeeding.getId());
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(failing.getRetryCount()).isEqualTo(1);
        assertThat(succeeding.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Kafka 발행이 실패하면 retryCount를 올리고 publishedAt은 비워 둔다")
    void publishPending_increasesRetryCountWhenSendFails() {
        // given
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
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
    @DisplayName("실패한 행만 있으면 그 키를 뺀 조회로 한 번 더 조회하고, 보낼 행이 없으면 실행을 끝낸다")
    void publishPending_queriesAgainWithoutFailedKeysAndEndsWhenNothingLeft() {
        // given
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        givenPending(List.of(outbox));
        givenFound(outbox);
        givenSendFails();

        // when
        publisher.publishPending();

        // then
        verify(outboxRepository, times(1)).findByPublishedAtIsNullOrderByCreatedAtAsc(any());
        verify(outboxRepository, times(1))
                .findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(eq(Set.of("key-1")), any());
        verify(kafkaTemplate, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("실패하는 행이 batchSize만큼 앞을 차지해도, 실패한 키를 빼고 다시 조회해서 뒤의 정상 행을 발행한다")
    void publishPending_publishesHealthyRowBeyondPageFilledWithFailingRows() {
        // given
        List<Outbox> all = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            all.add(pendingOutbox("margin.reserve-requested", "bad-" + i, "{}"));
        }
        Outbox healthy = pendingOutbox("margin.reserve-requested", "good-1", "{}");
        all.add(healthy);
        givenPending(all);
        when(kafkaTemplate.send(anyString(), startsWith("bad-"), anyString())).thenReturn(failedFuture());
        when(kafkaTemplate.send(anyString(), startsWith("good-"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        assertThat(healthy.getPublishedAt()).isNotNull();
        verify(kafkaTemplate, times(1)).send(anyString(), eq("good-1"), anyString());
    }

    @Test
    @DisplayName("보낸 행이 하나도 확인되지 않고 시간이 초과되면 Kafka가 응답하지 않는 것으로 보고 다음 반복 없이 실행을 끝낸다")
    void publishPending_endsRunWhenNothingConfirmedAndAckTimedOut() {
        // given
        publisher = newPublisher(Duration.ofMillis(50));
        List<Outbox> all = new ArrayList<>();
        for (int i = 0; i < BATCH_SIZE; i++) {
            all.add(pendingOutbox("margin.reserve-requested", "key-" + i, "{}"));
        }
        Outbox beyondPage = pendingOutbox("margin.reserve-requested", "key-beyond", "{}");
        all.add(beyondPage);
        givenPending(all);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, times(BATCH_SIZE)).send(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), eq("key-beyond"), anyString());
        verify(outboxRepository, never())
                .findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(anyCollection(), any());
    }

    @Test
    @DisplayName("send 호출이 한 번 예외를 던져도(예: 없는 토픽) 그 행만 실패로 기록하고 다른 행은 계속 보낸다")
    void publishPending_continuesWithOtherRowsWhenSendThrowsOnce() {
        // given
        Outbox badTopic = pendingOutbox("no-such-topic", "key-1", "{}");
        Outbox healthy = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(badTopic, healthy));
        givenFound(badTopic, healthy);
        when(kafkaTemplate.send(eq("no-such-topic"), anyString(), anyString())).thenThrow(new IllegalStateException("topic not present"));
        when(kafkaTemplate.send(eq("margin.reserve-requested"), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        assertThat(badTopic.getRetryCount()).isEqualTo(1);
        assertThat(badTopic.getPublishedAt()).isNull();
        assertThat(healthy.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("send 호출이 연달아 예외를 던지면 남은 전송을 멈추고, 보내지 않은 행은 실패로 세지 않는다")
    void publishPending_stopsRemainingSendsWhenSendThrowsConsecutively() {
        // given
        int failures = OutboxPublisher.MAX_CONSECUTIVE_SEND_FAILURES;
        List<Outbox> throwing = new ArrayList<>();
        for (int i = 0; i < failures; i++) {
            throwing.add(pendingOutbox("margin.reserve-requested", "key-" + i, "{}"));
        }
        Outbox remaining = pendingOutbox("margin.reserve-requested", "key-remaining", "{}");
        List<Outbox> all = new ArrayList<>(throwing);
        all.add(remaining);
        givenPending(all);
        givenFound(all.toArray(new Outbox[0]));
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker unreachable"));

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, times(failures)).send(anyString(), anyString(), anyString());
        verify(kafkaTemplate, never()).send(anyString(), eq("key-remaining"), anyString());
        throwing.forEach(outbox -> assertThat(outbox.getRetryCount()).isEqualTo(1));
        assertThat(remaining.getRetryCount()).isZero();
        assertThat(remaining.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("사이에 전송이 성공하면 연달아 실패한 횟수가 초기화되어 남은 행을 계속 보낸다")
    void publishPending_resetsConsecutiveFailuresAfterSuccessfulSend() {
        // given
        int failuresBelowLimit = OutboxPublisher.MAX_CONSECUTIVE_SEND_FAILURES - 1;
        List<Outbox> all = new ArrayList<>();
        for (int i = 0; i < failuresBelowLimit; i++) {
            all.add(pendingOutbox("bad-topic", "bad-a-" + i, "{}"));
        }
        Outbox healthy = pendingOutbox("margin.reserve-requested", "key-healthy", "{}");
        all.add(healthy);
        for (int i = 0; i < failuresBelowLimit; i++) {
            all.add(pendingOutbox("bad-topic", "bad-b-" + i, "{}"));
        }
        Outbox last = pendingOutbox("margin.reserve-requested", "key-last", "{}");
        all.add(last);
        givenPending(all);
        givenFound(all.toArray(new Outbox[0]));
        when(kafkaTemplate.send(eq("bad-topic"), anyString(), anyString())).thenThrow(new IllegalStateException("topic not present"));
        when(kafkaTemplate.send(eq("margin.reserve-requested"), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        assertThat(healthy.getPublishedAt()).isNotNull();
        assertThat(last.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 보낸 행이 있으면 send가 예외를 던져도 앞서 성공한 행은 발행 완료로 표시한다")
    void publishPending_marksRowsSentBeforeSendThrew() {
        // given
        Outbox sent = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox failing = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(sent, failing));
        givenFound(failing);
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenThrow(new IllegalStateException("producer closed"));

        // when
        publisher.publishPending();

        // then
        assertThat(sent.getPublishedAt()).isNotNull();
        assertThat(failing.getRetryCount()).isEqualTo(1);
        assertThat(failing.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("발행 성공 응답이 시간 안에 오지 않으면 실패로 보고 retryCount를 올린다")
    void publishPending_treatsSendTimeoutAsFailure() {
        // given
        publisher = newPublisher(Duration.ofMillis(50));
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
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
    @DisplayName("대기 중 인터럽트되면 인터럽트 상태를 되살리고, 종료 신호라서 retryCount는 올리지 않는다")
    @SuppressWarnings("unchecked")
    void publishPending_restoresInterruptFlagWithoutCountingFailure() throws Exception {
        // given
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
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
            assertThat(outbox.getRetryCount()).isZero();
            assertThat(outbox.getPublishedAt()).isNull();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 응답을 확인하지 못한 행은 모두 실패로 세지 않고 반복도 멈춘다")
    @SuppressWarnings("unchecked")
    void publishPending_doesNotCountUnconfirmedRowsWhenInterrupted() throws Exception {
        // given
        Outbox interrupted = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox unconfirmed = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(interrupted, unconfirmed));
        givenFound(interrupted, unconfirmed);
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new InterruptedException());
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(future);
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        try {
            // when
            publisher.publishPending();

            // then
            assertThat(interrupted.getRetryCount()).isZero();
            assertThat(unconfirmed.getRetryCount()).isZero();
            assertThat(unconfirmed.getPublishedAt()).isNull();
            verify(outboxRepository, times(1)).findByPublishedAtIsNullOrderByCreatedAtAsc(any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("이미 인터럽트된 상태면 조회도 전송도 하지 않는다")
    void publishPending_doesNothingWhenAlreadyInterrupted() {
        // given
        givenPending(List.of(pendingOutbox("margin.reserve-requested", "key-1", "{}")));
        Thread.currentThread().interrupt();

        try {
            // when
            publisher.publishPending();

            // then
            verify(outboxRepository, never()).findByPublishedAtIsNullOrderByCreatedAtAsc(any());
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("payload를 읽을 수 없는 행은 Kafka로 보내지 않고 retryCount를 올리며, 다른 키는 계속 보낸다")
    void publishPending_recordsFailureWhenMessageCannotBeCreated() {
        // given
        Outbox broken = pendingOutbox("margin.reserve-requested", "key-1", "{not-json");
        Outbox healthy = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(broken, healthy));
        givenFound(broken, healthy);
        givenSendSucceeds();

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, never()).send(anyString(), eq("key-1"), anyString());
        verify(kafkaTemplate).send(anyString(), eq("key-2"), anyString());
        assertThat(broken.getRetryCount()).isEqualTo(1);
        assertThat(broken.getPublishedAt()).isNull();
        assertThat(healthy.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 키의 행이 실패하면 같은 실행에서는 같은 키의 뒤 행을 보내지 않고, 다른 키는 보낸다")
    void publishPending_blocksLaterRowsOfSameKeyAfterFailure() {
        // given
        Outbox first = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox sameKey = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox otherKey = pendingOutbox("margin.reserve-requested", "key-2", "{}");
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
    @DisplayName("실패한 키는 같은 실행의 이후 반복에서도 다시 보내지 않아 retryCount가 연달아 오르지 않는다")
    void publishPending_doesNotRetryFailedKeyInLaterBatchesOfSameRun() {
        // given
        Outbox failing = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox first = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        Outbox second = pendingOutbox("margin.reserve-requested", "key-2", "{}");
        givenPending(List.of(failing, first, second));
        givenFound(failing, first, second);
        when(kafkaTemplate.send(anyString(), eq("key-1"), anyString())).thenReturn(failedFuture());
        when(kafkaTemplate.send(anyString(), eq("key-2"), anyString())).thenReturn(CompletableFuture.completedFuture(null));

        // when
        publisher.publishPending();

        // then
        verify(kafkaTemplate, times(1)).send(anyString(), eq("key-1"), anyString());
        verify(kafkaTemplate, times(2)).send(anyString(), eq("key-2"), anyString());
        assertThat(failing.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행 후 완료 표시에 실패해도 예외를 밖으로 던지지 않고, 같은 키의 뒤 행은 보내지 않는다")
    void publishPending_swallowsMarkPublishedFailureAndBlocksKey() {
        // given
        Outbox first = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        Outbox sameKey = pendingOutbox("margin.reserve-requested", "key-1", "{}");
        givenPending(List.of(first, sameKey));
        when(outboxRepository.markPublished(any(), anyCollection())).thenThrow(new IllegalStateException("db down"));
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
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{\"accountId\":\"a-1\"}");
        List<Outbox> rows = List.of(outbox);
        givenPending(rows);
        when(outboxRepository.markPublished(any(), anyCollection()))
                .thenThrow(new IllegalStateException("db down"))
                .thenAnswer(markPublishedAnswer(rows));
        givenSendSucceeds();

        // when
        publisher.publishPending();
        publisher.publishPending();

        // then
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, times(2)).send(eq("margin.reserve-requested"), eq("key-1"), messages.capture());
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
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
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
        Outbox outbox = pendingOutbox("margin.reserve-requested", "key-1", "{}");
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

    // DB처럼 동작하게 한다: 미발행 행만 돌려주고, 완료 표시하면 publishedAt이 채워진다
    private void givenPending(List<Outbox> outboxes) {
        when(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(any()))
                .thenAnswer(invocation -> {
                    Pageable page = invocation.getArgument(0);
                    return outboxes.stream().filter(outbox -> outbox.getPublishedAt() == null)
                            .limit(page.getPageSize()).toList();
                });
        when(outboxRepository.findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(anyCollection(), any()))
                .thenAnswer(invocation -> {
                    Collection<String> excludedKeys = invocation.getArgument(0);
                    Pageable page = invocation.getArgument(1);
                    return outboxes.stream().filter(outbox -> outbox.getPublishedAt() == null)
                            .filter(outbox -> !excludedKeys.contains(outbox.getMessageKey()))
                            .limit(page.getPageSize()).toList();
                });
        when(outboxRepository.markPublished(any(), anyCollection())).thenAnswer(markPublishedAnswer(outboxes));
    }

    private Answer<Integer> markPublishedAnswer(List<Outbox> outboxes) {
        return invocation -> {
            Instant publishedAt = invocation.getArgument(0);
            Collection<UUID> ids = invocation.getArgument(1);
            int updated = 0;
            for (Outbox outbox : outboxes) {
                if (ids.contains(outbox.getId()) && outbox.getPublishedAt() == null) {
                    outbox.markPublished(publishedAt);
                    updated++;
                }
            }
            return updated;
        };
    }

    // 실패 횟수 기록은 행을 다시 조회해서 올린다
    private void givenFound(Outbox... outboxes) {
        for (Outbox outbox : outboxes) {
            when(outboxRepository.findById(outbox.getId())).thenReturn(Optional.of(outbox));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Collection<UUID>> markedIdBatches(int expectedCalls) {
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(outboxRepository, times(expectedCalls)).markPublished(any(), captor.capture());
        return captor.getAllValues();
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
        Outbox outbox = Outbox.of("SUBSCRIPTION", UUID.randomUUID(), topic, messageKey, payload);
        ReflectionTestUtils.setField(outbox, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(outbox, "createdAt", Instant.parse("2026-10-27T06:59:58.123Z"));
        return outbox;
    }
}
