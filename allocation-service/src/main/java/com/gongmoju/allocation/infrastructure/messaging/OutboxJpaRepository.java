package com.gongmoju.allocation.infrastructure.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

// 발행 대기 이벤트 저장소
interface OutboxJpaRepository extends JpaRepository<Outbox, UUID> {

    // 미발행 행을 만든 순서대로 조회 (부분 인덱스 ix_outbox_unpublished)
    List<Outbox> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);

    // 위와 같되 messageKeys에 해당하는 키의 행은 뺀다. 이미 실패한 키가 조회 구간을 채우지 않게 한다 (비어 있으면 쓰지 않는다)
    List<Outbox> findByPublishedAtIsNullAndMessageKeyNotInOrderByCreatedAtAsc(Collection<String> messageKeys, Pageable pageable);

    // 발행에 성공한 행들을 한 번에 발행 완료로 표시한다. 이미 표시된 행은 건드리지 않는다. 트랜잭션 안에서 호출한다
    @Modifying
    @Query("update Outbox o set o.publishedAt = :publishedAt where o.id in :ids and o.publishedAt is null")
    int markPublished(@Param("publishedAt") Instant publishedAt, @Param("ids") Collection<UUID> ids);
}
