package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer가 처리한 이벤트를 기록하는 JPA Repository
 * 업무 처리와 동일한 트랜잭션에서 저장해 실패 시 함께 롤백한다.
 * (event_id, consumer_name) UNIQUE 제약조건으로 중복 기록을 방지한다.
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {

    // 중복이면 예외 없이 0행 (PostgreSQL은 UNIQUE 위반 뒤 같은 트랜잭션을 더 쓸 수 없어서 ON CONFLICT로 피한다)
    // 같은 키를 동시에 넣으면 뒤의 INSERT가 앞 트랜잭션의 커밋을 기다린 뒤 0행이 된다 (앞이 롤백되면 1행)
    // 네이티브 쿼리는 JPA Auditing을 거치지 않아 processed_at을 직접 채운다. 청약 서비스와 같은 방식
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (processed_event_id, event_id, consumer_name, topic, processed_at)
            VALUES (:id, :eventId, :consumerName, :topic, :processedAt)
            ON CONFLICT (event_id, consumer_name) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
                       @Param("consumerName") String consumerName, @Param("topic") String topic,
                       @Param("processedAt") Instant processedAt);
}
