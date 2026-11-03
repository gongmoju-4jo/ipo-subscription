package com.gongmoju.allocation.infrastructure.messaging;

import com.gongmoju.allocation.domain.event.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpaRepository;

    @Override
    public boolean insertIfAbsent(UUID eventId, String consumerName, String topic) {
        return jpaRepository.insertIfAbsent(UUID.randomUUID(), eventId, consumerName, topic, Instant.now()) == 1;
    }
}
