package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * 이벤트 처리 기록을 저장하는 JPA Repository.
 * (event_id, consumer_name) UNIQUE 제약조건으로 중복 기록을 방지한다.
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * 이벤트 처리 기록을 중복 없이 저장한다.
     * ON CONFLICT DO NOTHING으로 중복 시 예외 없이 삽입을 생략한다.
     * 네이티브 쿼리는 JPA Auditing을 거치지 않아 처리 시각을 직접 전달한다.
     *
     * @param id           이벤트 처리 기록의 PK
     * @param eventId      중복 여부를 판단할 이벤트 ID
     * @param consumerName 이벤트를 처리하는 Consumer 식별자
     * @param topic        원본 kafka 토픽명
     * @param processedAt  이벤트 처리 기록 시각
     * @return 삽입된 행 수 (신규 1, 중복 0)
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
