package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveBatchResult;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import com.gongmoju.account.infrastructure.config.KafkaConsumerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// margin.reserve-requested Consumer (Consumer 이름 account-margin-reserve = group-id, 이벤트 명세 3-2)
// 밀린 메시지를 묶어서(배치) 한 트랜잭션으로 처리한다 (#162). 건마다 하던 DB 커밋과 오프셋 커밋이 배치당 1번이 된다
// 값을 문자열로 받아 직접 파싱한다. 실패한 메시지가 DLT에 원본 JSON 그대로 쌓여야 원 토픽으로 재발행했을 때 같은 eventId로 처리되기 때문
// 재시도 여부는 KafkaConsumerConfig가 결정한다. 이곳에서는 실패한 메시지가 몇 번째인지를 BatchListenerFailedException(index)으로 알려서
// 그 건만 재시도, DLT로 가게 한다. 인덱스 없는 예외를 던지면 배치 전체가 재시도된 뒤 전부 DLT로 간다
// 재시도해도 결과가 같은 실패는 InvalidEventException으로 변환해 둔다 (BatchListenerFailedException의 원인으로 담아서 재시도 없이 바로 DLT)
@Slf4j
@Component
class MarginReserveRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final MarginReserveService marginReserveService;
    // 배치 크기 분포. 배치가 실제로 적용됐는지, 밀릴 때 얼마나 커지는지 본다
    private final DistributionSummary batchSize;
    // 배치 전체가 롤백돼서 건별 재처리로 들어간 횟수. 평소에는 0이어야 한다
    private final Counter fallbackCount;

    MarginReserveRequestedConsumer(ObjectMapper objectMapper, MarginReserveService marginReserveService, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.marginReserveService = marginReserveService;
        this.batchSize = DistributionSummary.builder("margin.reserve.batch.size")
                .description("증거금 인출 Consumer가 한 번에 처리한 메시지 수")
                .serviceLevelObjectives(1, 2, 5, 10, 20, 50, 100)
                .register(meterRegistry);
        this.fallbackCount = Counter.builder("margin.reserve.batch.fallback")
                .description("배치 처리가 실패해 건별로 다시 처리한 횟수")
                .register(meterRegistry);
    }

    // id: 리스너 이름 고정용. Consumer Group은 groupId가 우선이라 그대로
    // 배치 리스너라 리스너 지표(spring_kafka_listener)와 분산 추적 span이 나오지 않는다. 배치 크기, 처리량은 margin.reserve.batch.size로 본다
    // max.poll.records: 한 번에 받는 최대 건수. 이 리스너에만 적용한다. 기다려서 모으지 않고 이미 밀려 있는 만큼만 받는다
    // containerFactory: 정산 Consumer와 같은 공용 배치 팩토리 (KafkaConsumerConfig, #114). 배치 수신, 오프셋 배치 커밋, 에러 핸들러 적용,
    // 리스너 지표 끄기(1건 리스너의 추적 지표와 이름이 겹침)가 한곳에서 정해진다
    @KafkaListener(id = MarginReserveService.CONSUMER_NAME, topics = MarginReserveService.TOPIC, groupId = MarginReserveService.CONSUMER_NAME,
            containerFactory = KafkaConsumerConfig.BATCH_LISTENER_FACTORY,
            properties = "max.poll.records=${margin-reserve.batch.max-poll-records:100}")
    void consume(List<ConsumerRecord<String, String>> records) {
        // 앞에서부터 파싱한다. 잘못된 메시지를 만나면 거기서 멈추고, 그 앞 메시지는 먼저 처리한 뒤에 예외를 던진다
        // (먼저 던지면 앞 메시지가 처리되지 않은 채 그 뒤부터 다시 전달된다)
        List<ReserveMarginCommand> commands = new ArrayList<>(records.size());
        InvalidEventException invalid = null;
        for (ConsumerRecord<String, String> record : records) {
            try {
                commands.add(parse(record.value()).toCommand());
            } catch (InvalidEventException e) {
                log.error("[증거금 인출] 잘못된 메시지라 재시도 없이 DLT로 보냄. partition={}, offset={}, reason={}",
                        record.partition(), record.offset(), e.getMessage());
                invalid = e;
                break;
            }
        }

        process(commands);

        if (invalid != null) {
            // 잘못된 메시지의 위치(commands.size())를 알려서 그 건만 DLT로 보낸다. 그 앞 건은 이미 커밋돼 있다
            throw new BatchListenerFailedException("잘못된 메시지", invalid, commands.size());
        }
    }

    // 한 트랜잭션으로 처리한다. 실패하면 전체가 롤백된 상태라서 한 건씩 기존 경로로 다시 처리해 실패한 건을 가려낸다
    private void process(List<ReserveMarginCommand> commands) {
        if (commands.isEmpty()) {
            return;
        }
        batchSize.record(commands.size());

        long startNanos = System.nanoTime();
        try {
            ReserveBatchResult result = marginReserveService.reserveAll(commands);
            log.info("[증거금 인출] 배치 처리. 건수={}, 인출={}, 거절={}, 건너뜀={}, 걸린시간={}ms",
                    commands.size(), result.reserved(), result.rejected(), result.skipped(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            return;
        } catch (RuntimeException batchFailure) {
            fallbackCount.increment();
            log.warn("[증거금 인출] 배치 처리 실패, 한 건씩 다시 처리. 건수={}, reason={}", commands.size(), batchFailure.toString());
        }

        // 배치 단위 재시도는 하지 않는다. 재시도는 실패한 건의 1초, 2초, 4초뿐이다
        // 인덱스 앞 건은 여기서 건별 트랜잭션으로 커밋돼 있어서, 인덱스 앞까지 오프셋을 커밋해도 빠지는 건이 없다
        for (int i = 0; i < commands.size(); i++) {
            try {
                marginReserveService.reserve(commands.get(i));
            } catch (RuntimeException e) {
                throw new BatchListenerFailedException("증거금 인출 실패", e, i);
            }
        }
    }

    private MarginReserveRequestedMessage parse(String payload) {
        return KafkaMessageParser.parse(payload, objectMapper.readerFor(MarginReserveRequestedMessage.class));
    }
}
