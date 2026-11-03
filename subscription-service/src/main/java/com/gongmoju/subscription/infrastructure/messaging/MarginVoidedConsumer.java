package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.SubscriptionVoidedService;
import com.gongmoju.subscription.application.subscription.VoidedResult;
import com.gongmoju.subscription.domain.subscription.MarginVoided;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code margin.voided} Consumer (Consumer 이름 {@code subscription-margin-voided}, group-id도 같은 값).
 * 값을 문자열로 받아 직접 파싱함 ({@link SubscriptionRequestedConsumer}와 같은 이유로, DLT에 원본 JSON이 그대로 쌓임).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 함.
 * 재시도를 모두 소진해도 보상 착수 같은 추가 처리는 없고 DLT로만 보냄 ({@link MarginReservedCompensationRecoverer}는 {@code margin.reserved}만 처리함).
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MarginVoidedConsumer {

    private final ObjectMapper objectMapper;
    private final SubscriptionVoidedService voidedService;

    @KafkaListener(topics = MarginVoidedMessage.TOPIC, groupId = SubscriptionVoidedService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 해제 완료 응답 내용으로 변환함
        MarginVoided event = MessageSupport.read(objectMapper, payload, MarginVoidedMessage.class).toEvent();
        VoidedResult result = voidedService.complete(event);
        log.info("증거금 해제 응답 처리 완료. result={}, eventId={}, subscriptionId={}", result, event.eventId(), event.subscriptionId());
    }
}
