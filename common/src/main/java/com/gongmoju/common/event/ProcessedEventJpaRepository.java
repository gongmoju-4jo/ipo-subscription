package com.gongmoju.common.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * 처리한 이벤트({@link ProcessedEvent}) 저장소.
 * {@link ProcessedEventRepositoryAdapter}만 쓴다 (서비스는 포트 {@link ProcessedEventRepository}로 접근한다).
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * 같은 {@code (event_id, consumer_name)}이 없을 때만 저장한다. 트랜잭션 안에서 호출해야 한다.
     * <ul>
     *   <li>{@code ON CONFLICT (event_id, consumer_name) DO NOTHING}이라 중복이어도 예외 없이 0행을 반환한다.
     *       PostgreSQL은 UNIQUE 위반이 나면 같은 트랜잭션을 더 쓸 수 없어서, 예외 대신 0행으로 알려 주는 방식을 쓴다.</li>
     *   <li>네이티브 쿼리는 JPA Auditing을 거치지 않으므로 {@code processed_at}을 직접 채운다.</li>
     * </ul>
     *
     * @return 새로 저장했으면 1, 이미 있어서 건너뛰었으면 0
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
