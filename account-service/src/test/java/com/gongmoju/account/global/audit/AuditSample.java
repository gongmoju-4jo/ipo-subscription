package com.gongmoju.account.global.audit;

import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.util.UUID;

// BaseEntityAuditingTest 전용 엔티티
@Entity
public class AuditSample extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
}
