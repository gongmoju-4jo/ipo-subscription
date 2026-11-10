package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginVoidService;
import com.gongmoju.account.application.margin.VoidMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 증거금 반환 요청(margin.void-requested)을 수신하는 Kafka Consumer.
 *
 * <p>Consumer Group ID와 이벤트 처리 기록의 Consumer 이름을 동일하게 사용한다.
 *
 * <p>메시지를 문자열로 수신하여 파싱하므로 DLT에 원본 JSON을 보존한다.
 * DLT 메시지를 재발행하면 동일한 eventId로 재처리된다.
 *
 * <p>재시도 및 DLT 처리 정책은 KafkaConsumerConfig에서 관리한다.
 * 계좌 불일치 또는 이미 정산된 증거금(POSTED)은
 * InvalidEventException을 발생시켜 재시도 없이 DLT로 전송한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class MarginVoidRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final MarginVoidService marginVoidService;

    @KafkaListener(topics = MarginVoidService.TOPIC, groupId = MarginVoidService.CONSUMER_NAME)
    void consume(String payload) {
        VoidMarginCommand command;
        try {
            command = parse(payload).toCommand();
        } catch (InvalidEventException e) {
            log.error("[증거금 반환] 잘못된 메시지라 재시도 없이 DLT로 보냄. reason={}", e.getMessage());
            throw e;
        }
        log.debug("[증거금 반환] 메시지 수신. eventId={}, subscriptionId={}", command.eventId(), command.subscriptionId());
        marginVoidService.voidMargin(command);
    }

    private MarginVoidRequestedMessage parse(String payload) {
        return KafkaMessageParser.parse(payload, objectMapper.readerFor(MarginVoidRequestedMessage.class));
    }
}
