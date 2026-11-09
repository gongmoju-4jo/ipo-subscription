package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.outbox.Outbox;
import com.gongmoju.common.outbox.OutboxJpaRepository;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoClosedRecorder;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * {@code ipo.closed}를 {@code Outbox}에 기록함 (이벤트 명세 {@code ipo.closed} 절). 파티션 키는 {@code ipoId}, 집계 주체는 공모주({@code IPO}, ERD).
 * payload에는 도메인 필드만 담고 공통 필드({@code eventId}, {@code eventType}, {@code occurredAt})는 발행기가 붙임.
 */
@Component
class OutboxIpoClosedRecorder implements IpoClosedRecorder {

    static final String TOPIC = "ipo.closed";
    static final String AGGREGATE_TYPE = "IPO";

    // 이벤트 명세 시각 규칙: ISO-8601, +09:00, 밀리초 (예: 2026-10-27T16:00:00.000+09:00)
    private static final DateTimeFormatter CLOSED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.ofHours(9));

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    OutboxIpoClosedRecorder(OutboxJpaRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(Ipo ipo, long confirmedCount) {
        // closedAt은 청약 접수 마감 시각 (스케줄러가 마감을 처리한 시각인 occurredAt과 구분)
        Payload payload = new Payload(ipo.getId(), CLOSED_AT_FORMAT.format(ipo.getSubscriptionEndAt()), confirmedCount,
                ipo.getOfferPrice(), ipo.getRetailQuantity(), ipo.getEqualQuantity(), ipo.getMinQuantity());
        outboxRepository.save(Outbox.of(AGGREGATE_TYPE, ipo.getId(), TOPIC, ipo.getId().toString(), toJson(payload)));
    }

    private String toJson(Payload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ipo.closed payload를 만들 수 없습니다. ipoId=" + payload.ipoId(), e);
        }
    }

    /**
     * 이벤트 명세 {@code ipo.closed}의 도메인 필드.
     */
    private record Payload(UUID ipoId, String closedAt, long confirmedCount, long offerPrice, long retailQuantity,
                           long equalQuantity, long minQuantity) {
    }
}
