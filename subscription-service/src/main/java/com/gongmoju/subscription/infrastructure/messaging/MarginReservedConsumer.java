package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.ConfirmResult;
import com.gongmoju.subscription.application.subscription.SubscriptionConfirmService;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code margin.reserved} Consumer (Consumer 이름 {@code subscription-margin-reserved}, group-id도 같은 값).
 * 값을 문자열로 받아 직접 파싱함 (DLT에 원본 JSON이 그대로 쌓여, 원 토픽으로 재발행해도 같은 eventId로 처리됨).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MarginReservedConsumer {

    private final ObjectMapper objectMapper;
    private final SubscriptionConfirmService confirmService;

    @KafkaListener(topics = MarginReservedMessage.TOPIC, groupId = SubscriptionConfirmService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 확보 성공 응답 내용으로 변환함
        MarginReserved event = MessageSupport.read(objectMapper, payload, MarginReservedMessage.class).toEvent();
        ConfirmResult result = confirmService.confirm(event);
        log.info("증거금 확보 성공 응답 처리 완료. result={}, eventId={}, subscriptionId={}", result, event.eventId(), event.subscriptionId());
    }
}
