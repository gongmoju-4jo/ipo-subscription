package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * {@code subscription.requested} 메시지 본문 (이벤트 명세 1장 공통 필드 + 2-1). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 시각은 ISO-8601 {@code +09:00}, 밀리초 3자리로 고정한다 (예: {@code 2026-10-27T15:59:58.123+09:00}).
 */
public record SubscriptionRequestedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID requestId,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long requestedQuantity,
        String receivedAt
) {

    public static final String TOPIC = "subscription.requested";

    private static final DateTimeFormatter KST_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").withZone(ZoneOffset.ofHours(9));

    /**
     * @param event      접수 내용
     * @param eventId    메시지 고유 ID. Outbox를 쓰지 않으므로 발행할 때 새로 만든다 (재요청 중복은 requestId로 판단)
     * @param occurredAt 발행 시각
     */
    public static SubscriptionRequestedMessage of(SubscriptionRequested event, UUID eventId, Instant occurredAt) {
        return new SubscriptionRequestedMessage(eventId, TOPIC, KST_MILLIS.format(occurredAt),
                event.requestId(), event.subscriptionId(), event.userId(), event.accountId(), event.ipoId(),
                event.requestedQuantity(), KST_MILLIS.format(event.receivedAt()));
    }
}
