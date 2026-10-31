package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Consumer가 처리한 이벤트를 기록하는 JPA Repository
 * 업무 처리와 동일한 트랜잭션에서 저장해 실패 시 함께 롤백한다.
 * (event_id, consumer_name) UNIQUE 제약조건으로 중복 기록을 방지한다.
 */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {
}
