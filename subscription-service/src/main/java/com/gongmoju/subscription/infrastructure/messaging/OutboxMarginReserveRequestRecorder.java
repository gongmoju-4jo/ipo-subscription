package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.Subscription;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@code margin.reserve-requested}를 {@code Outbox}에 기록함 (이벤트 명세 2-1). 파티션 키는 {@code accountId}, 집계 주체는 청약({@code SUBSCRIPTION}, ERD).
 * payload에는 도메인 필드만 담고 공통 필드({@code eventId}, {@code eventType}, {@code occurredAt})는 발행기가 붙임.
 */
@Component
class OutboxMarginReserveRequestRecorder implements MarginReserveRequestRecorder {

    static final String TOPIC = "margin.reserve-requested";
    static final String AGGREGATE_TYPE = "SUBSCRIPTION";

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    OutboxMarginReserveRequestRecorder(OutboxJpaRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(Subscription subscription) {
        Payload payload = new Payload(subscription.getId(), subscription.getUserId(), subscription.getAccountId(),
                subscription.getIpo().getId(), subscription.getMarginAmount());
        outboxRepository.save(Outbox.of(AGGREGATE_TYPE, subscription.getId(), TOPIC,
                subscription.getAccountId().toString(), toJson(payload)));
    }

    private String toJson(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("margin.reserve-requested payload를 만들 수 없습니다. subscriptionId=" + payload.subscriptionId(), e);
        }
    }

    /**
     * 이벤트 명세 2-1 {@code margin.reserve-requested}의 도메인 필드. {@code amount}는 청약의 {@code margin_amount}와 같은 값.
     */
    private record Payload(UUID subscriptionId, UUID userId, UUID accountId, UUID ipoId, long amount) {
    }
}
