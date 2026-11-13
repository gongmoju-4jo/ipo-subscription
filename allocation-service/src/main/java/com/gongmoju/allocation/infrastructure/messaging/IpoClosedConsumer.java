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
 * <p>
 * 메시지 1건(공모주 1개 배정)이 몇 분씩 걸릴 수 있어서 이 리스너에만 Kafka 처리 시간 한도를 늘린다.
 * 한도({@code max.poll.interval.ms}, 기본 5분)를 넘기면 Kafka가 이 Consumer를 빠진 것으로 보고 파티션을 다른 Consumer에 넘겨,
 * 같은 배정이 동시에 다시 실행된다. 한 번에 1건만 받아서(`max.poll.records=1`) 한도가 메시지 1건 처리 시간에 대한 것이 되게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class IpoClosedConsumer {

    private final ObjectMapper objectMapper;
    private final IpoClosedService ipoClosedService;

    @KafkaListener(topics = IpoClosedMessage.TOPIC, groupId = AllocationRoundService.CONSUMER_NAME, properties = {
            "max.poll.interval.ms:${allocation.ipo-closed.max-poll-interval-ms}",
            "max.poll.records:1"})
    void consume(String payload) {
        // 필수 필드와 형식을 검증하면서 메시지를 마감 내용으로 바꾼다
        IpoClosed event = MessageSupport.read(objectMapper, payload, IpoClosedMessage.class).toEvent();
        IpoClosedResult result = ipoClosedService.handle(event);
        log.info("공모주 마감 이벤트 처리 완료. result={}, eventId={}, ipoId={}", result, event.eventId(), event.ipoId());
    }
}
