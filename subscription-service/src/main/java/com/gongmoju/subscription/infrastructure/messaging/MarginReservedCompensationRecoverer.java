package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.CompensationResult;
import com.gongmoju.subscription.application.subscription.SubscriptionCompensationService;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.util.UUID;

/**
 * 재시도를 모두 소진한 레코드를 처리하는 복구 핸들러 (이벤트 명세 3-5). {@code margin.reserved}는 증거금이 이미 인출된 상태라서
 * DLT로 보내기 전에 새 트랜잭션으로 보상 착수({@code COMPENSATING(CONFIRM_FAILED)} 전환과 {@code margin.void-requested} 기록)를 먼저 시도함.
 * 보상 착수도 실패하면 기존대로 DLT로 보내서 수동 재처리 대상으로 남김.
 * <p>
 * 보상하지 않고 바로 DLT로 보내는 경우
 * <ul>
 *   <li>{@code margin.reserved} 외의 토픽</li>
 *   <li>검증 실패({@link InvalidEventException}, {@link IllegalArgumentException}): 청약을 특정할 수 없거나 메시지가 잘못된 경우라 재처리해도 결과가 같음</li>
 * </ul>
 */
@Slf4j
public class MarginReservedCompensationRecoverer implements ConsumerRecordRecoverer {

    private final ConsumerRecordRecoverer deadLetterRecoverer;
    private final SubscriptionCompensationService compensationService;
    private final ObjectMapper objectMapper;

    public MarginReservedCompensationRecoverer(ConsumerRecordRecoverer deadLetterRecoverer,
                                               SubscriptionCompensationService compensationService,
                                               ObjectMapper objectMapper) {
        this.deadLetterRecoverer = deadLetterRecoverer;
        this.compensationService = compensationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        if (MarginReservedMessage.TOPIC.equals(record.topic()) && !isValidationFailure(exception) && startCompensation(record)) {
            return;
        }
        deadLetterRecoverer.accept(record, exception);
    }

    /**
     * @return 보상 착수를 시도해서 끝났으면 {@code true}, 실패했으면 {@code false} (DLT로 보내야 함)
     */
    private boolean startCompensation(ConsumerRecord<?, ?> record) {
        UUID subscriptionId = null;
        try {
            subscriptionId = readSubscriptionId(record);
            CompensationResult result = compensationService.startConfirmFailedCompensation(subscriptionId);
            log.info("재시도를 모두 소진한 margin.reserved의 보상 착수 처리 완료. result={}, subscriptionId={}", result, subscriptionId);
            return true;
        } catch (Exception e) {
            log.error("보상 착수에 실패해서 DLT로 보냄. subscriptionId={}, topic={}, partition={}, offset={}",
                    subscriptionId, record.topic(), record.partition(), record.offset(), e);
            return false;
        }
    }

    private UUID readSubscriptionId(ConsumerRecord<?, ?> record) {
        if (!(record.value() instanceof String payload)) {
            throw new InvalidEventException("메시지 본문이 문자열이 아닙니다.");
        }
        UUID subscriptionId = MessageSupport.read(objectMapper, payload, MarginReservedMessage.class).subscriptionId();
        MessageSupport.require(subscriptionId, "subscriptionId");
        return subscriptionId;
    }

    // 에러 핸들러는 리스너 예외를 ListenerExecutionFailedException으로 감싸서 넘김
    private boolean isValidationFailure(Exception exception) {
        Throwable cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
                ? exception.getCause() : exception;
        return cause instanceof InvalidEventException || cause instanceof IllegalArgumentException;
    }
}
