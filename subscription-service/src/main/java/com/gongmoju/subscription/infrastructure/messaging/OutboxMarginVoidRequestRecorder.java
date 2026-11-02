package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
import com.gongmoju.subscription.domain.subscription.Subscription;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code margin.void-requested}를 {@code Outbox}에 기록함 (이벤트 명세 2-1). 파티션 키는 {@code accountId}, 집계 주체는 청약({@code SUBSCRIPTION}, ERD).
 * payload에는 도메인 필드만 담고 공통 필드({@code eventId}, {@code eventType}, {@code occurredAt})는 발행기가 붙임.
 */
@Component
class OutboxMarginVoidRequestRecorder implements MarginVoidRequestRecorder {

    static final String TOPIC = "margin.void-requested";
    static final String AGGREGATE_TYPE = "SUBSCRIPTION";

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    OutboxMarginVoidRequestRecorder(OutboxJpaRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(Subscription subscription) {
        if (subscription.getCancelReason() == null) {
            throw new IllegalStateException("해제 사유가 없는 청약은 해제를 요청할 수 없습니다. subscriptionId=" + subscription.getId());
        }
        Payload payload = new Payload(subscription.getId(), subscription.getAccountId(), subscription.getCancelReason());
        outboxRepository.save(Outbox.of(AGGREGATE_TYPE, subscription.getId(), TOPIC,
                subscription.getAccountId().toString(), toJson(payload)));
    }

    private String toJson(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("margin.void-requested payload를 만들 수 없습니다. subscriptionId=" + payload.subscriptionId(), e);
        }
    }

    /**
     * 이벤트 명세 2-1 {@code margin.void-requested}의 도메인 필드. {@code accountId}는 청약의 {@code account_id}와 같은 값.
     */
    private record Payload(UUID subscriptionId, UUID accountId, CancelReason reason) {
    }
}
