package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.domain.event.ProcessedEventRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

// ProcessedEventRecorder의 JPA 구현. 호출한 트랜잭션에 같이 묶인다
@Slf4j
@Component
@RequiredArgsConstructor
class JpaProcessedEventRecorder implements ProcessedEventRecorder {

    private final ProcessedEventJpaRepository processedEventRepository;

    // 1행 삽입이면 처음 받은 이벤트, 0행이면 이미 처리한 이벤트
    @Override
    public boolean insertIfAbsent(UUID eventId, String consumerName, String topic) {

        boolean inserted = processedEventRepository.insertIfAbsent(
                UUID.randomUUID(), eventId, consumerName, topic, Instant.now()) == 1;

        log.debug("[처리 기록] 삽입. eventId={}, consumer={}, topic={}, inserted={}", eventId, consumerName, topic, inserted);
        return inserted;
    }
}
