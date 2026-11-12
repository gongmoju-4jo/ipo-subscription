package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.ipo.AllocationFinishedResult;
import com.gongmoju.subscription.application.ipo.IpoAllocationFinishService;
import com.gongmoju.subscription.domain.ipo.AllocationFinished;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code allocation.finished} Consumer (Consumer 이름 {@code subscription-allocation-finished}, group-id도 같은 값).
 * 값을 문자열로 받아 직접 파싱함 (DLT에 원본 JSON이 그대로 쌓여, 원 토픽으로 재발행해도 같은 eventId로 처리됨).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AllocationFinishedConsumer {

    private final ObjectMapper objectMapper;
    private final IpoAllocationFinishService allocationFinishService;

    @KafkaListener(topics = AllocationFinishedMessage.TOPIC, groupId = IpoAllocationFinishService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 배정 완료 내용으로 변환함
        AllocationFinished event = MessageSupport.read(objectMapper, payload, AllocationFinishedMessage.class).toEvent();
        AllocationFinishedResult result = allocationFinishService.finish(event);
        log.info("배정 완료 이벤트 처리 완료. result={}, eventId={}, ipoId={}", result, event.eventId(), event.ipoId());
    }
}
