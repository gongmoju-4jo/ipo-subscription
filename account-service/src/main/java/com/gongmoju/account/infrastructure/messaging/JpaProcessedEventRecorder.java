package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.domain.event.ProcessedEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// ProcessedEventRecorder의 JPA 구현. 호출한 트랜잭션에 같이 묶인다
@Component
@RequiredArgsConstructor
class JpaProcessedEventRecorder implements ProcessedEventRecorder {

    private final ProcessedEventJpaRepository processedEventRepository;

    @Override
    public void record(UUID eventId, String consumerName, String topic) {
        processedEventRepository.save(new ProcessedEvent(eventId, consumerName, topic));
    }
}
