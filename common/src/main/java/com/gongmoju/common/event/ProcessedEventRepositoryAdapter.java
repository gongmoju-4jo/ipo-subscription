package com.gongmoju.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * {@link ProcessedEventRepository}의 JPA 구현체. 호출한 서비스의 DB 트랜잭션에 참여해서
 * 업무 변경이 롤백되면 처리 기록도 같이 롤백된다. {@link CommonProcessedEventAutoConfiguration}에서 등록한다.
 */
class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpaRepository;

    ProcessedEventRepositoryAdapter(ProcessedEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public boolean insertIfAbsent(UUID eventId, String consumerName, String topic) {
        return jpaRepository.insertIfAbsent(UUID.randomUUID(), eventId, consumerName, topic, Instant.now()) == 1;
    }
}
