package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.CompensationResult;
import com.gongmoju.subscription.application.subscription.SubscriptionCompensationService;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

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
        if (MarginReservedMessage.TOPIC.equals(record.topic()) && !isValidationFailure(exception) && startCompensation(record, exception)) {
            return;
        }
        deadLetterRecoverer.accept(record, exception);
    }

    /**
     * 보상이 시작되면 원본 메시지가 DLT에 남지 않으므로, 어떤 메시지가 왜 실패했는지 추적할 수 있게 {@code eventId}와 원래 실패 원인, 레코드 위치를 로그에 남김.
     *
     * @return 보상 착수를 시도해서 끝났으면 {@code true}, 실패했으면 {@code false} (DLT로 보내야 함)
     */
    private boolean startCompensation(ConsumerRecord<?, ?> record, Exception exception) {
        MarginReservedMessage message = null;
        try {
            message = read(record);
            CompensationResult result = compensationService.startConfirmFailedCompensation(message.subscriptionId());
            log.warn("재시도를 모두 소진한 margin.reserved의 보상 착수 처리 완료. result={}, subscriptionId={}, eventId={}, topic={}, partition={}, offset={}, 원래 실패 원인={}",
                    result, message.subscriptionId(), message.eventId(), record.topic(), record.partition(), record.offset(), rootCauseMessage(exception));
            return true;
        } catch (Exception e) {
            log.error("보상 착수에 실패해서 DLT로 보냄. subscriptionId={}, eventId={}, topic={}, partition={}, offset={}, 원래 실패 원인={}",
                    message == null ? null : message.subscriptionId(), message == null ? null : message.eventId(),
                    record.topic(), record.partition(), record.offset(), rootCauseMessage(exception), e);
            return false;
        }
    }

    private MarginReservedMessage read(ConsumerRecord<?, ?> record) {
        if (!(record.value() instanceof String payload)) {
            throw new InvalidEventException("메시지 본문이 문자열이 아닙니다.");
        }
        MarginReservedMessage message = MessageSupport.read(objectMapper, payload, MarginReservedMessage.class);
        MessageSupport.require(message.subscriptionId(), "subscriptionId");
        return message;
    }

    // 원인 체인의 가장 안쪽 예외를 "클래스: 메시지"로 요약함 (스택 전체는 재시도 로그에 이미 있음)
    private String rootCauseMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    // 에러 핸들러는 리스너 예외를 ListenerExecutionFailedException으로 감싸서 넘김. 재시도 제외 예외도 원인 체인 전체를 따라가며 판단하므로(DefaultErrorHandler),
    // 여기서도 체인 전체를 확인해야 바로 DLT로 가야 할 검증 실패를 재시도 소진으로 착각하지 않음
    private boolean isValidationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof InvalidEventException || cause instanceof IllegalArgumentException) {
                return true;
            }
        }
        return false;
    }
}
