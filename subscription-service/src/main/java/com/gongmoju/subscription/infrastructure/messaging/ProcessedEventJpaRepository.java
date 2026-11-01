package com.gongmoju.subscription.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

// 처리한 이벤트 저장소
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * 네이티브 쿼리는 JPA Auditing을 거치지 않으므로 {@code processed_at}을 직접 채움.
     * {@code ON CONFLICT} 대상을 {@code (event_id, consumer_name)}로 명시하여 중복은 예외 없이 0행으로 반환됨 (PostgreSQL은 UNIQUE 위반 뒤 같은 트랜잭션을 쓸 수 없음). 트랜잭션 안에서 호출해야 함.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (processed_event_id, event_id, consumer_name, topic, processed_at)
            VALUES (:id, :eventId, :consumerName, :topic, :processedAt)
            ON CONFLICT (event_id, consumer_name) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
                       @Param("consumerName") String consumerName, @Param("topic") String topic,
                       @Param("processedAt") Instant processedAt);
}
