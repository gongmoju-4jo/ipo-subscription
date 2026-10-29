package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

// 발행 대기 이벤트 저장소
interface OutboxJpaRepository extends JpaRepository<Outbox, UUID> {

    // 미발행 행을 만든 순서대로 조회 (부분 인덱스 ix_outbox_unpublished)
    List<Outbox> findByPublishedAtIsNullOrderByCreatedAtAsc(Pageable pageable);
}
