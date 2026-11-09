package com.gongmoju.common.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 발행 대기 이벤트({@link Outbox}) 저장소.
 * 서비스의 이벤트별 기록 클래스가 저장에 쓰고, 발행기가 미발행 행 조회와 발행 완료 표시에 쓴다.
 */
public interface OutboxJpaRepository extends JpaRepository<Outbox, UUID> {

    /**
     * 미발행 행을 만든 순서대로 조회한다 (부분 인덱스 ix_outbox_unpublished).
     */
    List<Outbox> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    /**
     * 미발행 행을 만든 순서대로 조회하되, {@code messageKeys}에 해당하는 키의 행은 뺀다.
     * 이미 실패한 키가 조회 구간을 채우지 않게 하려는 것이다. {@code messageKeys}가 비어 있으면 쓰지 않는다.
     */
    List<Outbox> findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(Collection<String> messageKeys, Pageable pageable);

    /**
     * 발행에 성공한 행들을 한 번에 발행 완료로 표시한다. 이미 표시된 행은 건드리지 않는다. 트랜잭션 안에서 호출해야 한다.
     *
     * @param publishedAt 발행 완료 시각
     * @param ids         발행에 성공한 행의 ID
     * @return 새로 표시한 행 수
     */
    @Modifying
    @Query("update Outbox o set o.publishedAt = :publishedAt where o.id in :ids and o.publishedAt is null")
    int markPublished(@Param("publishedAt") Instant publishedAt, @Param("ids") Collection<UUID> ids);
}
