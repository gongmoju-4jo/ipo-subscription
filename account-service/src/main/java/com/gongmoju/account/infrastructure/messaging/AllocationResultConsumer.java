package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginSettleService;
import com.gongmoju.account.application.margin.SettleMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import com.gongmoju.account.infrastructure.config.KafkaConsumerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 공모주 배정 결과(allocation.result)를 수신하는 Kafka Consumer.
 * <p>
 * 메시지를 문자열로 수신하여 검증한 후 정산 서비스에 전달한다.
 * Consumer Group ID는 이벤트 처리 기록의 Consumer 이름과 동일하게 사용한다.
 * 재시도 및 DLT 처리 정책은 KafkaConsumerConfig에서 관리한다.
 * <p>
 * 청약 서비스도 동일한 토픽을 별도 Consumer Group으로 구독하므로,
 * 두 서비스의 이벤트 처리는 서로 독립적이다.
 * <p>
 * 다음과 같은 검증 실패는 InvalidEventException으로 처리하며,
 * 재시도 없이 DLT로 전송한다.
 * <ul>
 *   <li>메시지 형식 오류: 필수 필드 누락, 소수 금액, 수량 합계 불일치</li>
 *   <li>데이터 불일치: Margin 없음, 계좌 또는 공모주 불일치</li>
 *   <li>금액 초과: 배정 대금이 증거금보다 큰 경우</li>
 *   <li>상태 충돌: 기존 정산 금액 불일치, 반환된 증거금에 배정이 발생한 경우</li>
 * </ul>
 *
 * <p>원본 JSON을 DLT에 보존하여 오류 분석 및 재처리에 활용한다.
 * 원본 메시지를 다시 발행하면 동일한 eventId로 재처리를 시도할 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AllocationResultConsumer {

    private final ObjectMapper objectMapper;
    private final MarginSettleService marginSettleService;

    // 메시지를 batch size(max.poll.records)건씩 수신해서 트랜잭션 1개로 정산한다 (#114)
    // 1건이라도 실패하면 배치 트랜잭션 전체를 롤백하고 1건씩 다시 처리해서, 실패한 건만 재시도, DLT 대상이 되게 한다
    @KafkaListener(topics = MarginSettleService.TOPIC, groupId = MarginSettleService.CONSUMER_NAME,
            containerFactory = KafkaConsumerConfig.BATCH_LISTENER_FACTORY,
            properties = "max.poll.records=${margin.settle.batch-size}")
    void consume(List<String> payloads) {
        try {
            settleAsBatch(payloads);
        } catch (RuntimeException e) {
            log.warn("[증거금 정산] 배치 처리 실패, 1건씩 다시 처리함. size={}, reason={}", payloads.size(), e.getMessage());
            settleOneByOne(payloads);
        }
    }

    // 배치 경로: 전체를 변환한 후 트랜잭션 1개로 정산한다
    // 잘못된 메시지가 있으면 변환 단계에서 예외가 발생해 1건씩 처리로 전환된다
    private void settleAsBatch(List<String> payloads) {
        List<SettleMarginCommand> commands = payloads.stream().map(this::toCommand).toList();
        marginSettleService.settleBatch(commands);
        log.debug("[증거금 정산] 배치 정산 완료. size={}", commands.size());
    }

    // 1건씩 처리 경로: 건마다 settle()의 트랜잭션으로 처리한다
    // 실패 위치를 BatchListenerFailedException으로 전달하면, 에러 핸들러가 이전 건의 오프셋은 커밋하고 실패 건만 재시도, DLT 처리한 후 다음 건부터 다시 수신한다
    private void settleOneByOne(List<String> payloads) {
        for (int index = 0; index < payloads.size(); index++) {
            try {
                marginSettleService.settle(toCommand(payloads.get(index)));
            } catch (RuntimeException e) {
                if (e instanceof InvalidEventException) {
                    log.error("[증거금 정산] 잘못된 메시지라 재시도 없이 DLT로 보냄. reason={}", e.getMessage());
                }
                throw new BatchListenerFailedException("[증거금 정산] 배치 " + index + "번째 메시지 처리 실패", e, index);
            }
        }
    }

    private SettleMarginCommand toCommand(String payload) {
        SettleMarginCommand command = parse(payload).toCommand();
        log.debug("[증거금 정산] 메시지 수신. eventId={}, subscriptionId={}", command.eventId(), command.subscriptionId());
        return command;
    }

    private AllocationResultMessage parse(String payload) {
        // 금액에 소수가 수신되면 버림 처리되지 않게 거부한다 (45000.5 → 45000 방지)
        return KafkaMessageParser.parse(payload, objectMapper.readerFor(AllocationResultMessage.class)
                .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
    }
}
