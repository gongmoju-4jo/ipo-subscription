package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.AcceptResult;
import com.gongmoju.subscription.application.subscription.SubscriptionAcceptService;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code subscription.requested} 접수 Consumer (Consumer 이름 {@code subscription-requested}, group-id는 {@code spring.kafka.consumer.group-id}).
 * 값을 문자열로 받아 직접 파싱함. 실패한 메시지가 DLT에 원본 JSON 그대로 쌓여야 원 토픽으로 재발행해도 같은 {@code eventId}, {@code requestId}로 처리되기 때문.
 * 재시도 대상 분류는 {@code KafkaConsumerConfig}의 에러 핸들러가 하고, 이 클래스는 재시도해도 소용없는 실패를 {@link InvalidEventException}으로 바꿔 던짐.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class SubscriptionRequestedConsumer {

    // 동시 요청으로 발생하는 이중청약 UNIQUE 위반. 롤백 후 재시도하면 기존 청약을 찾아 REJECTED로 저장됨
    static final String ACTIVE_SUBSCRIPTION_CONSTRAINT = "uk_subscription_active";

    // 이벤트 명세 3-2의 Consumer 이름. 리스너마다 직접 지정
    static final String CONSUMER_NAME = "subscription-requested";

    private final ObjectMapper objectMapper;
    private final SubscriptionAcceptService acceptService;

    @KafkaListener(topics = SubscriptionRequestedMessage.TOPIC, groupId = CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 접수 내용으로 변환함
        SubscriptionRequested event = parse(payload).toEvent();
        try {
            AcceptResult result = acceptService.accept(event);
            log.info("접수 처리 완료. result={}, requestId={}, subscriptionId={}", result, event.requestId(), event.subscriptionId());
        } catch (DataIntegrityViolationException e) {
            if (isActiveSubscriptionViolation(e)) {
                throw e;
            }
            // `NOT NULL`, FK, CHECK 위반은 재처리해도 같은 결과이므로 재시도 없이 DLT로 보냄
            throw new InvalidEventException("재시도할 수 없는 무결성 위반입니다. requestId=" + event.requestId(), e);
        }
    }

    private SubscriptionRequestedMessage parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("메시지 본문이 비어 있습니다.");
        }
        try {
            SubscriptionRequestedMessage message = objectMapper.readValue(payload, SubscriptionRequestedMessage.class);
            if (message == null) {
                throw new InvalidEventException("메시지 본문이 JSON 객체가 아닙니다.");
            }
            return message;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("메시지 JSON을 읽을 수 없습니다: " + e.getOriginalMessage(), e);
        }
    }

    private boolean isActiveSubscriptionViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && ACTIVE_SUBSCRIPTION_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
