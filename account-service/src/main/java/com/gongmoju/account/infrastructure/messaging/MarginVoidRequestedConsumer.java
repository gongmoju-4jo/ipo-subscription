package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginVoidService;
import com.gongmoju.account.application.margin.VoidMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 증거금 반환 요청(margin.void-requested) Consumer.
 * <p>
 * Consumer 이름 account-margin-void = group-id
 * 값을 문자열로 받아 직접 파싱하여 DLT에 원본 JSON이 그대로 저장된다.
 * (원 토픽으로 재발행하면 같은 eventId로 재처리 가능)
 * <p>
 * 재시도 여부는 KafkaConsumerConfig가 정한다.
 * <p>
 * 계좌 불일치, margin status = POSTED는
 * 서비스가 InvalidEventException을 던져 재시도 없이 DLT로 간다.
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
        if (payload == null || payload.isBlank()) {
            throw new InvalidEventException("메시지 본문이 비어 있습니다.");
        }
        try {
            MarginVoidRequestedMessage message = objectMapper.readValue(payload, MarginVoidRequestedMessage.class);
            if (message == null) {
                throw new InvalidEventException("메시지 본문이 JSON 객체가 아닙니다.");
            }
            return message;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("메시지 JSON을 읽을 수 없습니다: " + e.getOriginalMessage());
        }
    }
}
