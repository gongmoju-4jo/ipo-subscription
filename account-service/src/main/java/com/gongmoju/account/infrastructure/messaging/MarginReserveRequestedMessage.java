package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

// margin.reserve-requested 메시지 본문 (이벤트 명세 1장 공통 필드 + 2-1). 공통 필드와 도메인 필드를 한 단계로 펼친다
// 필수 값 누락, eventType과 토픽 불일치, 금액 1 미만, ISO-8601이 아닌 시각은 재처리해도 결과가 같아서 InvalidEventException (바로 DLT)
// UUID 형식 오류는 역직렬화에서 걸리고, amount가 없으면 0으로 읽혀 여기서 걸린다
record MarginReserveRequestedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        UUID userId,
        UUID accountId,
        UUID ipoId,
        long amount
) {

    ReserveMarginCommand toCommand() {
        require(eventId, "eventId");
        if (!MarginReserveService.TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + MarginReserveService.TOPIC + "가 아닙니다: " + eventType);
        }
        requireTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        require(userId, "userId");
        require(accountId, "accountId");
        require(ipoId, "ipoId");
        if (amount < 1) {
            throw new InvalidEventException("amount는 1 이상이어야 합니다: " + amount);
        }
        return new ReserveMarginCommand(eventId, subscriptionId, userId, accountId, ipoId, amount);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new InvalidEventException("필수 필드가 없습니다: " + field);
        }
    }

    private static void requireTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("필수 필드가 없습니다: " + field);
        }
        try {
            OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("시각 형식이 올바르지 않습니다: " + field + "=" + value);
        }
    }
}
