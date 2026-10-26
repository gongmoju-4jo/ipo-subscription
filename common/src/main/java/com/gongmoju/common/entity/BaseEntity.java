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

import java.time.Instant;

/**
 * 공통 Audit 필드.
 * <p>
 * *_at은 {@link Instant}로 저장한다 (DB {@code TIMESTAMPTZ}). 서버, DB 시간대 설정과 관계없이 같은 시각을 가리키고, 응답에서 KST로 바꿔 내보낸다.
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
    private Instant createdAt;

    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    private Instant updatedAt;

    @LastModifiedBy
    @Column(length = 100)
    private String updatedBy;
}
