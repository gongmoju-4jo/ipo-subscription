package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.domain.event.ProcessedEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * ProcessedEventRecorder의 JPA 구현체.
 * 호출 서비스의 DB 트랜잭션에 참여해 업무 실패 시 처리 기록도 함께 롤백한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class JpaProcessedEventRecorder implements ProcessedEventRecorder {

    private final ProcessedEventJpaRepository processedEventRepository;

    /**
     * INSERT와 중복 검사를 원자적으로 수행해 동일 이벤트의 중복 처리를 방지한다.
     *
     * @param eventId      중복 여부를 판단할 이벤트 ID
     * @param consumerName 이벤트를 처리하는 Consumer 식별자
     * @param topic        원본 Kafka 토픽명
     * @return true면 신규 기록 삽입, false면 이미 존재하는 처리 기록
     */
    @Override
    public boolean insertIfAbsent(UUID eventId, String consumerName, String topic) {

        boolean inserted = processedEventRepository.insertIfAbsent(
                UUID.randomUUID(), eventId, consumerName, topic, Instant.now()) == 1;

        log.debug("[이벤트 처리 기록] 삽입. eventId={}, consumer={}, topic={}, inserted={}", eventId, consumerName, topic, inserted);
        return inserted;
    }
}
