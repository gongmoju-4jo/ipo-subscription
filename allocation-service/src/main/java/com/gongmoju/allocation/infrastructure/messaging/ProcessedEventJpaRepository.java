package com.gongmoju.allocation.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

// 처리한 이벤트 저장소
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * 네이티브 쿼리는 JPA Auditing을 거치지 않으므로 {@code processed_at}을 직접 채운다. 트랜잭션 안에서 호출해야 한다.
     * 같은 {@code (event_id, consumer_name)}가 있으면 UNIQUE 위반 대신 0행으로 끝난다 (PostgreSQL은 UNIQUE 위반 뒤 같은 트랜잭션을 쓸 수 없음).
     * 충돌 대상을 적지 않은 것은 테스트 H2(PostgreSQL 모드)가 대상 지정을 지원하지 않아서다. PK는 새 UUID라 이 UNIQUE 말고는 충돌할 일이 없다.
     * 테이블 이름 앞의 {@code {h-schema}}는 Hibernate가 {@code hibernate.default_schema}로 바꾼다 (테스트 H2는 접속 URL의 currentSchema가 없음).
     */
    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}processed_event (processed_event_id, event_id, consumer_name, topic, processed_at)
            VALUES (:id, :eventId, :consumerName, :topic, :processedAt)
            ON CONFLICT DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId,
                       @Param("consumerName") String consumerName, @Param("topic") String topic,
                       @Param("processedAt") Instant processedAt);
}
