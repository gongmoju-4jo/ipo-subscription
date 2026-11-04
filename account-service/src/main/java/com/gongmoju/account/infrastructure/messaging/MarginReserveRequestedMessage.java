package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Kafka로 수신한 증거금 인출 요청 메시지 본문. (margin.reserve-requested)
 * <p>
 * 필수 필드와 이벤트 형식을 검증한 후 ReserveMarginCommand로 변환한다.
 * 검증 실패 시 InvalidEventException을 발생, Kafka 오류 처리기가 재시도 없이 DLT로 전송된다.
 * <p>
 * amount값이 없으면 0으로 읽혀 금액 검사에서 걸려 DLT로 간다.
 *
 * @param eventId        이벤트 고유 ID
 * @param eventType      이벤트 유형
 * @param occurredAt     이벤트 발생 시각 (ISO 8601)
 * @param subscriptionId 인출 대상 청약 ID
 * @param userId         요청자 ID
 * @param accountId      인출 대상 계좌 ID
 * @param ipoId          청약 대상 공모주 ID
 * @param amount         인출할 금액 (원, 1 이상)
 */
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
