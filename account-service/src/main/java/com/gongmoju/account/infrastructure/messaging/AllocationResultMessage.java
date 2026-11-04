package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.MarginSettleService;
import com.gongmoju.account.application.margin.SettleMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Kafka로 받은 배정 결과 메시지 본문 (allocation.result).
 * <p>
 * 필수 필드와 이벤트 형식을 검증한 후 SettleMarginCommand로 변환한다.
 * 검증 실패 시 InvalidEventException을 발생, Kafka 오류 처리기가 재시도 없이 DLT로 전송된다.
 * <p>
 * 수량은 사용하지 않지만 명세 제약(0 이상, 균등 + 비례 = 최종, 0주면 대금 0)이 깨지면
 * 대금도 믿을 수 없어서 같이 검사한다.
 * 누락을 0과 구분하려고 숫자는 Long으로 받는다.
 *
 * @param eventId                       이벤트 고유 ID
 * @param eventType                     이벤트 유형
 * @param occurredAt                    이벤트 발생 시각 (ISO 8601)
 * @param ipoId                         정산 대상 청약의 공모주 ID
 * @param subscriptionId                정산할 청약 ID
 * @param accountId                     정산 대상 계좌 ID
 * @param equalAllocatedQuantity        균등배정 수량 (0 이상)
 * @param proportionalAllocatedQuantity 비례배정 수량 (0 이상
 * @param allocatedQuantity             배정 수량 = 균등 + 비례, 0주 가능
 * @param allocatedAmount               배정 대금 = 배정 수량 * 공모가
 */
record AllocationResultMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID ipoId,
        UUID subscriptionId,
        UUID accountId,
        Long equalAllocatedQuantity,
        Long proportionalAllocatedQuantity,
        Long allocatedQuantity,
        Long allocatedAmount
) {

    SettleMarginCommand toCommand() {
        require(eventId, "eventId");
        if (!MarginSettleService.TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + MarginSettleService.TOPIC + "가 아닙니다: " + eventType);
        }
        requireTime(occurredAt, "occurredAt");
        require(ipoId, "ipoId");
        require(subscriptionId, "subscriptionId");
        require(accountId, "accountId");
        long equal = requireNonNegative(equalAllocatedQuantity, "equalAllocatedQuantity");
        long proportional = requireNonNegative(proportionalAllocatedQuantity, "proportionalAllocatedQuantity");
        long quantity = requireNonNegative(allocatedQuantity, "allocatedQuantity");
        long amount = requireNonNegative(allocatedAmount, "allocatedAmount");

        if (equal + proportional != quantity) {
            throw new InvalidEventException("allocatedQuantity가 균등 + 비례 수량과 다릅니다: "
                    + equal + " + " + proportional + " != " + quantity);
        }
        // 공모가는 0보다 크므로 0주와 대금 0은 같이 와야 한다
        if ((quantity == 0) != (amount == 0)) {
            throw new InvalidEventException("allocatedQuantity와 allocatedAmount가 맞지 않습니다: "
                    + "allocatedQuantity=" + quantity + ", allocatedAmount=" + amount);
        }
        return new SettleMarginCommand(eventId, subscriptionId, accountId, ipoId, amount);
    }

    private static long requireNonNegative(Long value, String field) {
        require(value, field);
        if (value < 0) {
            throw new InvalidEventException("0 이상이어야 합니다: " + field + "=" + value);
        }
        return value;
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
