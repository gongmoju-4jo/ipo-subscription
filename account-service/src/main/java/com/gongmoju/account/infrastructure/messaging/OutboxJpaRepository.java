package com.gongmoju.account.infrastructure.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// 미발행 행 조회 메서드는 발행기 작업에서 추가
interface OutboxJpaRepository extends JpaRepository<Outbox, UUID> {
}
