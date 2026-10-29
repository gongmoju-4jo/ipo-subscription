package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequestedPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code subscription.requested}를 Kafka에 직접 발행한다 (Outbox 없음, D-13). 키는 userId라서 같은 회원의 요청은 순서대로 처리된다.
 * acks=all, idempotence 설정은 application.yml에 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaSubscriptionRequestedPublisher implements SubscriptionRequestedPublisher {

    // Producer의 delivery.timeout.ms(10초)보다 길게 둬서 보통은 Producer 쪽 실패가 먼저 온다
    private static final long SEND_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 브로커 저장 확인까지 기다린다. 저장을 확인하지 못하면(타임아웃 포함) 500으로 응답한다.
     * 타임아웃이면 실제로는 저장됐을 수 있지만, 클라이언트가 같은 requestId로 다시 보내면 같은 subscriptionId라서 안전하다.
     *
     * @throws BusinessException 직렬화 또는 전송에 실패하면 INTERNAL_SERVER_ERROR(500)
     */
    @Override
    public void publish(SubscriptionRequested event) {
        SubscriptionRequestedMessage message = SubscriptionRequestedMessage.of(event, UUID.randomUUID(), Instant.now());
        String key = event.userId().toString();
        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(SubscriptionRequestedMessage.TOPIC, key, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishFailed(message, e);
        } catch (JsonProcessingException | ExecutionException | TimeoutException | RuntimeException e) {
            throw publishFailed(message, e);
        }
    }

    private BusinessException publishFailed(SubscriptionRequestedMessage message, Exception e) {
        log.error("subscription.requested 발행 실패 subscriptionId={} requestId={} eventId={}",
                message.subscriptionId(), message.requestId(), message.eventId(), e);
        return new BusinessException(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }
}
