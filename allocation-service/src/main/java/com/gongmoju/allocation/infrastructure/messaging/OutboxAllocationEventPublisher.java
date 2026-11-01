package com.gongmoju.allocation.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.allocation.domain.allocation.Allocation;
import com.gongmoju.allocation.domain.allocation.AllocationEventPublisher;
import com.gongmoju.allocation.domain.allocation.AllocationRound;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 배정 이벤트를 Outbox에 기록한다 (이벤트 명세 allocation.result, allocation.finished / ERD outbox aggregate_type = ALLOCATION)
// Kafka 전송은 OutboxPublisher가 한다. 공통 필드(eventId, eventType, occurredAt)도 발행기가 붙인다
@Component
@RequiredArgsConstructor
class OutboxAllocationEventPublisher implements AllocationEventPublisher {

    static final String AGGREGATE_TYPE = "ALLOCATION";
    static final String TOPIC_RESULT = "allocation.result";
    static final String TOPIC_FINISHED = "allocation.finished";

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // 청약 1건 단위라 aggregate_id = allocation_id, 키 = accountId
    @Override
    public void publishResult(Allocation allocation) {
        AllocationResultPayload payload = new AllocationResultPayload(
                allocation.getIpoId(), allocation.getSubscriptionId(), allocation.getAccountId(),
                allocation.getEqualAllocatedQuantity(), allocation.getProportionalAllocatedQuantity(),
                allocation.getAllocatedQuantity(), allocation.getAllocatedAmount());
        save(allocation.getId(), TOPIC_RESULT, allocation.getAccountId(), payload);
    }

    // 회차 단위라 aggregate_id = allocation_round_id, 키 = ipoId
    @Override
    public void publishFinished(AllocationRound allocationRound, long totalAllocatedQuantity, long subscriptionCount) {
        AllocationFinishedPayload payload = new AllocationFinishedPayload(
                allocationRound.getIpoId(), totalAllocatedQuantity, subscriptionCount);
        save(allocationRound.getId(), TOPIC_FINISHED, allocationRound.getIpoId(), payload);
    }

    private void save(UUID aggregateId, String topic, UUID messageKey, Object payload) {
        outboxRepository.save(Outbox.of(AGGREGATE_TYPE, aggregateId, topic, messageKey.toString(), toJson(payload)));
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox payload를 만들 수 없습니다. payload=" + payload, e);
        }
    }

    record AllocationResultPayload(
            UUID ipoId,
            UUID subscriptionId,
            UUID accountId,
            long equalAllocatedQuantity,
            long proportionalAllocatedQuantity,
            long allocatedQuantity,
            long allocatedAmount
    ) {
    }

    record AllocationFinishedPayload(
            UUID ipoId,
            long totalAllocatedQuantity,
            long subscriptionCount
    ) {
    }
}
