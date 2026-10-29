package com.gongmoju.account.infrastructure.messaging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

// 미발행 Outbox 행을 Kafka로 보낸다. 최소 한 번(at-least-once) 발행이라 중복은 Consumer가 (eventId, consumerName)으로 거른다
// Kafka 호출은 DB 트랜잭션 밖에서 하고, 결과 반영(발행 완료, 실패 횟수)만 행마다 짧은 트랜잭션으로 한다
// 단일 인스턴스를 전제로 한다 (여러 인스턴스면 같은 행을 중복 조회할 수 있다)
@Slf4j
@Component
class OutboxPublisher {

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
        List<Outbox> pending = outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(
                PageRequest.ofSize(properties.batchSize()));

        // 같은 키(같은 파티션)의 순서를 지키기 위해, 한 행이 실패하면 이번 실행에서는 같은 키의 뒤 행을 보내지 않는다
        Set<String> blockedKeys = new HashSet<>();
        for (Outbox outbox : pending) {
            // 종료 중 인터럽트가 걸렸으면 남은 행은 시도하지 않는다 (시도하지 않은 행의 실패 횟수가 오르지 않게)
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (blockedKeys.contains(outbox.getMessageKey())) {
                continue;
            }
            if (!publish(outbox)) {
                blockedKeys.add(outbox.getMessageKey());
            }
        }
    }

    private boolean publish(Outbox outbox) {
        try {
            String message = messageFactory.create(outbox);
            kafkaTemplate.send(outbox.getTopic(), outbox.getMessageKey(), message)
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(outbox, e);
            return false;
        } catch (Exception e) {
            recordFailure(outbox, e);
            return false;
        }
        return markPublished(outbox);
    }

    // 발행은 됐는데 완료 표시를 못 하면 다음 실행에서 다시 발행된다 (중복은 Consumer가 거른다)
    private boolean markPublished(Outbox outbox) {
        try {
            transactionTemplate.executeWithoutResult(status ->
                    outboxRepository.findById(outbox.getId())
                            .ifPresent(found -> found.markPublished(Instant.now())));
            return true;
        } catch (Exception e) {
            log.error("Outbox 발행 완료 표시 실패 (다음 실행에서 다시 발행됨). outboxId={}, topic={}",
                    outbox.getId(), outbox.getTopic(), e);
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
}
