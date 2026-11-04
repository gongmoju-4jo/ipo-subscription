package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.AllocateResult;
import com.gongmoju.subscription.application.subscription.SubscriptionAllocateService;
import com.gongmoju.subscription.domain.subscription.AllocationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code allocation.result} Consumer (Consumer 이름 {@code subscription-allocation-result}, group-id도 같은 값).
 * 계좌 서비스({@code account-allocation-result})와 group-id가 달라서 두 서비스가 모든 메시지를 각각 받음.
 * 값을 문자열로 받아 직접 파싱함 ({@link SubscriptionRequestedConsumer}와 같은 이유로, DLT에 원본 JSON이 그대로 쌓임).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AllocationResultConsumer {

    private final ObjectMapper objectMapper;
    private final SubscriptionAllocateService allocateService;

    @KafkaListener(topics = AllocationResultMessage.TOPIC, groupId = SubscriptionAllocateService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 배정 결과로 변환함
        AllocationResult event = MessageSupport.read(objectMapper, payload, AllocationResultMessage.class).toEvent();
        AllocateResult result = allocateService.allocate(event);
        log.info("배정 결과 처리 완료. result={}, eventId={}, subscriptionId={}", result, event.eventId(), event.subscriptionId());
    }
}
