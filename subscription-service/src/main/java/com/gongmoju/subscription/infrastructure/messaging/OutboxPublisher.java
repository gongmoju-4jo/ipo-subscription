package com.gongmoju.subscription.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// 미발행 Outbox 행을 Kafka로 보낸다. 최소 한 번(at-least-once) 발행이라 중복은 Consumer가 (eventId, consumerName)으로 거른다
// 한 번의 반복(batch)은 키마다 가장 오래된 행 하나씩을 응답을 기다리지 않고 연달아 보내고, 응답을 모아서 성공한 행을 한 번의 UPDATE로 완료 표시한다
// 같은 키의 행은 한 번에 하나만 전송 중이라 순서가 뒤집히지 않는다 (같은 키의 다음 행은 바로 다음 반복에서 나간다)
// 보낼 행이 남아 있으면 쉬지 않고 다음 반복을 한다. 이미 실패한 키는 조회에서 빼서, 실패한 행이 조회 구간을 채워도 뒤의 정상 행이 발행된다
// 더 보낼 행이 없거나 Kafka가 응답하지 않는 것으로 보이면 실행을 끝내서 interval만큼 쉰다
// Kafka 호출은 DB 트랜잭션 밖에서 하고, 결과 반영(발행 완료, 실패 횟수)만 짧은 트랜잭션으로 한다
// 단일 인스턴스를 전제로 한다 (여러 인스턴스면 같은 행을 중복 조회할 수 있다)
@Slf4j
@Component
class OutboxPublisher {

    // send 호출이 이 횟수만큼 연달아 예외를 던지면 Kafka에 닿지 못하는 것으로 보고 이번 실행의 남은 전송을 멈춘다
    // 한두 번이면 그 행만의 문제일 수 있다 (예: 없는 토픽). 그 행 하나 때문에 전체 발행이 멈추지 않게 한다
    static final int MAX_CONSECUTIVE_SEND_FAILURES = 3;

    private final OutboxJpaRepository outboxRepository;
    private final OutboxMessageFactory messageFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxPublisherProperties properties;

    OutboxPublisher(OutboxJpaRepository outboxRepository,
                    OutboxMessageFactory messageFactory,
                    KafkaTemplate<String, String> kafkaTemplate,
                    TransactionTemplate transactionTemplate,
                    OutboxPublisherProperties properties) {
        this.outboxRepository = outboxRepository;
        this.messageFactory = messageFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    // 이전 실행이 끝난 뒤 interval만큼 쉬고 다시 실행한다 (실행이 겹치지 않는다)
    @Scheduled(fixedDelayString = "${outbox.publisher.interval:PT1S}")
    void publishPending() {
        // 이번 실행에서 실패한 키. 이후 반복에서는 조회하지 않는다 (실패한 행을 반복마다 다시 두드리지 않고, 같은 키의 순서를 지킨다)
        // 반복마다 이 키들이 늘거나 행이 발행 완료되어서, 실행은 반드시 끝난다
        Set<String> blockedKeys = new HashSet<>();
        // 종료 중 인터럽트가 걸렸으면 시도하지 않는다 (시도하지 않은 행의 실패 횟수가 오르지 않게)
        while (!Thread.currentThread().isInterrupted()) {
            if (publishBatch(blockedKeys)) {
                return;
            }
        }
    }

    // 한 번의 반복을 하고, 이번 실행을 끝내야 하면 true (보낼 행이 없음, 인터럽트, 전송 호출 연속 실패, Kafka 무응답, 완료 표시 실패)
    private boolean publishBatch(Set<String> blockedKeys) {
        Pageable page = PageRequest.ofSize(properties.batchSize());
        // 실패한 키는 조회에서 뺀다. 조회한 뒤에 빼면 실패한 행이 batchSize만큼 앞을 차지할 때 뒤의 정상 행이 조회되지 않는다
        List<Outbox> pending = blockedKeys.isEmpty()
                ? outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(page)
                : outboxRepository.findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(blockedKeys, page);
        List<Outbox> targets = oldestPerKey(pending);
        if (targets.isEmpty()) {
            return true;
        }

        List<InFlight> inFlight = new ArrayList<>();
        boolean stop = sendAll(targets, inFlight, blockedKeys);

        // 전체에 sendTimeout 하나를 적용한다 (행마다 누적해서 기다리지 않는다)
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        List<Outbox> confirmed = new ArrayList<>();
        boolean timedOut = false;
        for (InFlight sent : inFlight) {
            Outbox outbox = sent.outbox();
            try {
                sent.future().get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                confirmed.add(outbox);
            } catch (InterruptedException e) {
                // 종료 신호이지 Kafka 발행 실패가 아니라서 실패 횟수를 올리지 않는다
                // 응답을 확인하지 못한 행은 다음 실행에서 다시 보낸다 (중복은 Consumer가 거른다)
                Thread.currentThread().interrupt();
                stop = true;
                break;
            } catch (Exception e) {
                timedOut |= isTimeout(e);
                recordFailure(outbox, e);
                blockedKeys.add(outbox.getMessageKey());
            }
        }
        boolean marked = markPublished(confirmed, blockedKeys);
        // 하나도 확인하지 못하고 시간이 초과되면 Kafka가 응답하지 않는 것이다. 다음 구간을 계속 보내면 반복마다 sendTimeout만큼 기다린다
        // 완료 표시에 실패하면 DB가 문제다. 계속 보내면 발행만 되고 표시가 안 되어 중복이 쌓인다
        return stop || !marked || (confirmed.isEmpty() && timedOut);
    }

    // 전송 응답 대기 시간이 지났거나, Producer가 전달 제한 시간(delivery.timeout.ms)으로 포기한 경우
    private static boolean isTimeout(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException || t instanceof org.apache.kafka.common.errors.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    // 키마다 가장 오래된 행 하나만 고른다
    private List<Outbox> oldestPerKey(List<Outbox> pending) {
        Set<String> seenKeys = new HashSet<>();
        List<Outbox> targets = new ArrayList<>();
        for (Outbox outbox : pending) {
            if (seenKeys.add(outbox.getMessageKey())) {
                targets.add(outbox);
            }
        }
        return targets;
    }

    // 응답을 기다리지 않고 연달아 보낸다. 이번 실행을 멈춰야 하면 true
    private boolean sendAll(List<Outbox> targets, List<InFlight> inFlight, Set<String> blockedKeys) {
        int consecutiveSendFailures = 0;
        for (Outbox outbox : targets) {
            if (Thread.currentThread().isInterrupted()) {
                return true;
            }
            String message;
            try {
                message = messageFactory.create(outbox);
            } catch (Exception e) {
                // 이 행만의 문제라 다른 키는 계속 보낸다
                recordFailure(outbox, e);
                blockedKeys.add(outbox.getMessageKey());
                continue;
            }
            try {
                inFlight.add(new InFlight(outbox, kafkaTemplate.send(outbox.getTopic(), outbox.getMessageKey(), message)));
                consecutiveSendFailures = 0;
            } catch (Exception e) {
                // 전송 호출이 막히거나 실패한다. 행마다 max.block.ms씩 기다리지 않도록, 연달아 실패하면 남은 전송을 멈춘다
                recordFailure(outbox, e);
                blockedKeys.add(outbox.getMessageKey());
                if (++consecutiveSendFailures >= MAX_CONSECUTIVE_SEND_FAILURES) {
                    return true;
                }
            }
        }
        return false;
    }

    // 발행은 됐는데 완료 표시를 못 하면 다음 실행에서 다시 발행된다 (중복은 Consumer가 거른다). 표시에 실패하면 false
    private boolean markPublished(List<Outbox> confirmed, Set<String> blockedKeys) {
        if (confirmed.isEmpty()) {
            return true;
        }
        List<UUID> ids = confirmed.stream().map(Outbox::getId).toList();
        try {
            transactionTemplate.executeWithoutResult(status -> outboxRepository.markPublished(Instant.now(), ids));
            return true;
        } catch (Exception e) {
            log.error("Outbox 발행 완료 표시 실패 (다음 실행에서 다시 발행됨). 행 수={}", confirmed.size(), e);
            confirmed.forEach(outbox -> blockedKeys.add(outbox.getMessageKey()));
            return false;
        }
    }

    private void recordFailure(Outbox outbox, Exception cause) {
        Integer retryCount;
        try {
            retryCount = transactionTemplate.execute(status ->
                    outboxRepository.findById(outbox.getId())
                            .map(found -> {
                                found.increaseRetryCount();
                                return found.getRetryCount();
                            })
                            .orElse(null));
        } catch (Exception e) {
            log.error("Outbox 실패 횟수 기록 실패. outboxId={}, topic={}", outbox.getId(), outbox.getTopic(), e);
            retryCount = null;
        }

        if (retryCount != null && retryCount >= properties.alertThreshold()) {
            // 알림 기준: 계속 재시도하지만 운영자가 봐야 하는 상태
            log.error("Outbox 발행 연속 실패 (알림 기준 {}회 이상). outboxId={}, topic={}, retryCount={}",
                    properties.alertThreshold(), outbox.getId(), outbox.getTopic(), retryCount, cause);
        } else {
            log.warn("Outbox 발행 실패 (다음 실행에서 재시도). outboxId={}, topic={}, retryCount={}",
                    outbox.getId(), outbox.getTopic(), retryCount, cause);
        }
    }

    private record InFlight(Outbox outbox, CompletableFuture<SendResult<String, String>> future) {
    }
}
