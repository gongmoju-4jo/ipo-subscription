package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.MarginVoidService;
import com.gongmoju.account.application.margin.VoidMarginCommand;
import com.gongmoju.account.domain.margin.MarginVoidReason;
import com.gongmoju.account.global.exception.InvalidEventException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Kafka로 수신한 증거금 반환 요청 메시지 본문. (margin.void-requested)
 * <p>
 * 필수 필드와 이벤트 형식을 검증한 후
 * VoidMarginCommand로 변환한다.
 * 검증 실패 시 InvalidEventException을 발생, 재시도 없이 DLT로 전송된다.
 * <p>
 * reason은 문자열로 받아 MarginVoidReason으로 변환한다.
 *
 * @param eventId        이벤트 고유 ID
 * @param eventType      이벤트 유형
 * @param occurredAt     이벤트 발생 시각 (ISO 8601)
 * @param subscriptionId 반환 대상 청약 ID
 * @param accountId      반환 대상 계좌 ID
 * @param reason         반환 요청 사유 (CONFIRM_FAILED, TIMEOUT, USER_CANCEL 중 하나)
 */
record MarginVoidRequestedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        UUID accountId,
        String reason
) {

    VoidMarginCommand toCommand() {
        require(eventId, "eventId");
        if (!MarginVoidService.TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + MarginVoidService.TOPIC + "가 아닙니다: " + eventType);
        }
        requireTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        require(accountId, "accountId");
        return new VoidMarginCommand(eventId, subscriptionId, accountId, toReason(reason));
    }

    private static MarginVoidReason toReason(String value) {
        require(value, "reason");
        try {
            return MarginVoidReason.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("reason 값이 올바르지 않습니다: " + value);
        }
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
