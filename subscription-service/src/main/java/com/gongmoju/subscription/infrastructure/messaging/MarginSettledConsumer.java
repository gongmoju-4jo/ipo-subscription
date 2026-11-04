package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.RefundResult;
import com.gongmoju.subscription.application.subscription.SubscriptionRefundService;
import com.gongmoju.subscription.domain.subscription.MarginSettled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code margin.settled} Consumer (Consumer 이름 {@code subscription-margin-settled}, group-id도 같은 값).
 * 값을 문자열로 받아 직접 파싱함 ({@link SubscriptionRequestedConsumer}와 같은 이유로, DLT에 원본 JSON이 그대로 쌓임).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MarginSettledConsumer {

    private final ObjectMapper objectMapper;
    private final SubscriptionRefundService refundService;

    @KafkaListener(topics = MarginSettledMessage.TOPIC, groupId = SubscriptionRefundService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 정산 결과로 변환함
        MarginSettled event = MessageSupport.read(objectMapper, payload, MarginSettledMessage.class).toEvent();
        RefundResult result = refundService.refund(event);
        log.info("정산 결과 처리 완료. result={}, eventId={}, subscriptionId={}", result, event.eventId(), event.subscriptionId());
    }
}
