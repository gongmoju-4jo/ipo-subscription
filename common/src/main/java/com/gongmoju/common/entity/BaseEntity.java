package com.gongmoju.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 공통 Audit 필드.
 * <p>
 * *_by는 요청자 회원 ID, 요청이 없으면(Consumer, 스케줄러) SYSTEM:{서비스 이름} ({@link com.gongmoju.common.audit.RequestAuditorAware}).
 * 소프트 삭제(deleted_*)는 두지 않는다. 취소, 거절은 상태로 남긴다.
 * ledger_entry는 수정이 없어 이 클래스를 상속하지 않는다.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @LastModifiedBy
    @Column(length = 100)
    private String updatedBy;
}
