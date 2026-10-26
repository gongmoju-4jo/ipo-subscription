package com.gongmoju.account.global.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// BaseEntityAuditingTest 전용 Repository
public interface AuditSampleRepository extends JpaRepository<AuditSample, UUID> {
}
