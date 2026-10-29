package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// Consumer가 업무 처리와 같은 트랜잭션에서 저장한다. (event_id, consumer_name) UNIQUE 위반이면 이미 처리한 이벤트
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, UUID> {
}
