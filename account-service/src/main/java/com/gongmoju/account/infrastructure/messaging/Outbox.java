package com.gongmoju.account.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

// 이벤트 발행 대기 (Transactional Outbox). 업무 변경과 같은 트랜잭션에 저장하고 발행기가 Kafka로 보낸다
// 서비스 3개가 같은 모양 (ERD v1.1 공통 outbox). 미발행 부분 인덱스(WHERE published_at IS NULL)는 schema.sql에 둔다
@Getter
@Entity
@Table(name = "outbox")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

    // 메시지의 eventId로 그대로 사용
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id")
    private UUID id;

    @Column(nullable = false, length = 30)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, length = 100)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // NULL이면 미발행
    private Instant publishedAt;

    @Column(nullable = false)
    private int retryCount;
}
