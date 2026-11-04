package com.gongmoju.allocation.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.allocation.application.allocation.AllocationRoundService;
import com.gongmoju.allocation.application.allocation.IpoClosedResult;
import com.gongmoju.allocation.application.allocation.IpoClosedService;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code ipo.closed} Consumer (Consumer 이름 {@code allocation-ipo-closed}, group-id도 같은 값).
 * 값을 문자열로 받아 직접 파싱한다 (DLT에 원본 JSON이 그대로 쌓이도록).
 * 예외는 삼키지 않고 던지며, 재시도와 DLT 전송은 {@code KafkaConsumerConfig}의 에러 핸들러가 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IpoClosedConsumer {

    private final ObjectMapper objectMapper;
    private final IpoClosedService ipoClosedService;

    @KafkaListener(topics = IpoClosedMessage.TOPIC, groupId = AllocationRoundService.CONSUMER_NAME)
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 마감 내용으로 바꾼다
        IpoClosed event = MessageSupport.read(objectMapper, payload, IpoClosedMessage.class).toEvent();
        IpoClosedResult result = ipoClosedService.handle(event);
        log.info("공모주 마감 이벤트 처리 완료. result={}, eventId={}, ipoId={}", result, event.eventId(), event.ipoId());
    }
}
