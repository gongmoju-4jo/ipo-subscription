package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// 발행 대기 이벤트 저장소. 발행기 조회 메서드는 4단계에서 추가
interface OutboxJpaRepository extends JpaRepository<Outbox, UUID> {
}
