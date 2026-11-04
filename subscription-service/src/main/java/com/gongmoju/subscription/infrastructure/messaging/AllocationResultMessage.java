package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionAllocateService;
import com.gongmoju.subscription.domain.subscription.AllocationResult;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code allocation.result} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code allocation.result} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 수량과 금액은 누락과 {@code 0}(0주 배정)을 구분해야 하므로 {@link Long}으로 받음.
 */
public record AllocationResultMessage(
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

    public static final String TOPIC = SubscriptionAllocateService.TOPIC;

    /**
     * 메시지를 배정 결과로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 시각 형식 오류, 음수 수량이나 금액, 균등 + 비례가 최종 배정 수량과 다른 경우는
     * 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함. UUID 형식 오류는 역직렬화 단계에서 걸러짐.
     * 저장된 청약과 비교해야 하는 검증(공모주, 계좌, 청약 수량, 공모가)은 처리 서비스가 함.
     *
     * @return 배정 결과
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public AllocationResult toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(ipoId, "ipoId");
        require(subscriptionId, "subscriptionId");
        require(accountId, "accountId");
        long equal = requireNotNegative(equalAllocatedQuantity, "equalAllocatedQuantity");
        long proportional = requireNotNegative(proportionalAllocatedQuantity, "proportionalAllocatedQuantity");
        long allocated = requireNotNegative(allocatedQuantity, "allocatedQuantity");
        long amount = requireNotNegative(allocatedAmount, "allocatedAmount");
        // 둘 다 0 이상이라 더해서 음수가 되면 범위를 넘은 것. 넘어도 최종 배정 수량과 같을 수 없으므로 아래 비교에서 걸러짐
        if (equal + proportional != allocated) {
            throw new InvalidEventException("균등 + 비례 배정 수량이 최종 배정 수량과 다릅니다: equalAllocatedQuantity=" + equal
                    + ", proportionalAllocatedQuantity=" + proportional + ", allocatedQuantity=" + allocated);
        }
        return new AllocationResult(eventId, subscriptionId, ipoId, accountId, allocated, amount);
    }

    private static long requireNotNegative(Long value, String field) {
        require(value, field);
        if (value < 0) {
            throw new InvalidEventException(field + "는 0 이상이어야 합니다: " + value);
        }
        return value;
    }
}
