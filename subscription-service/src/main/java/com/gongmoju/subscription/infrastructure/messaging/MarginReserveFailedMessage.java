package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionRejectService;
import com.gongmoju.subscription.domain.subscription.MarginReserveFailed;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.global.exception.InvalidEventException;

import java.util.Set;
import java.util.UUID;

import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.subscription.infrastructure.messaging.MessageSupport.require;

/**
 * {@code margin.reserve-failed} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code margin.reserve-failed} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 */
public record MarginReserveFailedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID subscriptionId,
        String reason
) {

    public static final String TOPIC = SubscriptionRejectService.TOPIC;

    // 이중청약(ALREADY_SUBSCRIBED)은 이 이벤트가 아니라 청약 API가 저장할 때 판정하므로 허용하지 않음
    private static final Set<RejectReason> ALLOWED_REASONS = Set.of(RejectReason.INSUFFICIENT_BALANCE, RejectReason.ACCOUNT_NOT_FOUND);

    /**
     * 메시지를 확보 실패 응답 내용으로 변환함. 필수 필드({@code eventId}, {@code eventType}, {@code occurredAt} 및 도메인 필드) 누락,
     * {@code eventType}과 토픽 이름 불일치, 시각 형식 오류, {@code INSUFFICIENT_BALANCE}와 {@code ACCOUNT_NOT_FOUND} 외의 {@code reason}은
     * 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송함. UUID 형식 오류는 역직렬화 단계에서 걸러짐.
     *
     * @return 확보 실패 응답 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못한 경우
     */
    public MarginReserveFailed toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(subscriptionId, "subscriptionId");
        return new MarginReserveFailed(eventId, subscriptionId, parseReason());
    }

    private RejectReason parseReason() {
        if (reason == null || reason.isBlank()) {
            throw new InvalidEventException("필수 필드가 없습니다: reason");
        }
        for (RejectReason allowed : ALLOWED_REASONS) {
            if (allowed.name().equals(reason)) {
                return allowed;
            }
        }
        throw new InvalidEventException("reason이 올바르지 않습니다: " + reason);
    }
}
