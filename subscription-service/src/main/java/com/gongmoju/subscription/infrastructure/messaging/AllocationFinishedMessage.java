package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.ipo.IpoAllocationFinishService;
import com.gongmoju.subscription.domain.ipo.AllocationFinished;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code allocation.finished} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code allocation.finished} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 수량은 누락을 구분하려고 {@code Long}으로 받는다.
 */
public record AllocationFinishedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID ipoId,
        Long totalAllocatedQuantity,
        Long subscriptionCount
) {

    public static final String TOPIC = IpoAllocationFinishService.TOPIC;

    /**
     * 메시지를 배정 완료 내용으로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 시각 형식 오류, 음수 수량은 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함.
     * UUID 형식 오류는 역직렬화 단계에서 걸러짐.
     *
     * @return 배정 완료 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public AllocationFinished toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(ipoId, "ipoId");
        return new AllocationFinished(eventId, ipoId,
                requireNotNegative(totalAllocatedQuantity, "totalAllocatedQuantity"),
                requireNotNegative(subscriptionCount, "subscriptionCount"));
    }

    private static long requireNotNegative(Long value, String field) {
        require(value, field);
        if (value < 0) {
            throw new InvalidEventException(field + "는 0 이상이어야 합니다: " + value);
        }
        return value;
    }
}
