package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// margin.reserve-requested Consumer (Consumer 이름 account-margin-reserve = group-id, 이벤트 명세 3-2)
// 값을 문자열로 받아 직접 파싱한다. 실패한 메시지가 DLT에 원본 JSON 그대로 쌓여야 원 토픽으로 재발행했을 때 같은 eventId로 처리되기 때문
// 재시도 여부는 KafkaConsumerConfig가 정하고, 여기서는 재시도해도 소용없는 실패를 InvalidEventException으로 바꿔 던진다
@Slf4j
@Component
@RequiredArgsConstructor
class MarginReserveRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final MarginReserveService marginReserveService;

    // id: 리스너 지표(spring_kafka_listener) name 라벨 고정용. Consumer Group은 groupId가 우선이라 그대로
    @KafkaListener(id = MarginReserveService.CONSUMER_NAME, topics = MarginReserveService.TOPIC, groupId = MarginReserveService.CONSUMER_NAME)
    void consume(String payload) {
        ReserveMarginCommand command;
        try {
            command = parse(payload).toCommand();
        } catch (InvalidEventException e) {
            log.error("[증거금 인출] 잘못된 메시지라 재시도 없이 DLT로 보냄. reason={}", e.getMessage());
            throw e;
        }
        log.debug("[증거금 인출] 메시지 수신. eventId={}, subscriptionId={}", command.eventId(), command.subscriptionId());
        marginReserveService.reserve(command);
    }

    private MarginReserveRequestedMessage parse(String payload) {
        return KafkaMessageParser.parse(payload, objectMapper.readerFor(MarginReserveRequestedMessage.class));
    }
}
