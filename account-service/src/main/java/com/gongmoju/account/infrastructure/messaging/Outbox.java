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

    // Kafka 이벤트의 고유 ID (eventId)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id")
    private UUID id;

    // todo: enum으로 변경
    // 이벤트와 관련된 도메인 종류 (MARGIN, ALLOCATION, SUBSCRIPTION, IPO)
    @Column(nullable = false, length = 30)
    private String aggregateType;

    // 이벤트와 관련된 도메인 객체의 고유 ID (ex. marginId)
    @Column(nullable = false)
    private UUID aggregateId;

    // 발생한 사건의 종류 (ex. margin.reserved)
    @Column(nullable = false, length = 50)
    private String eventType;

    // 이벤트를 발행할 Kafka 토픽 이름
    @Column(nullable = false, length = 100)
    private String topic;

    // Kafka 메시지의 파티션을 결정하는 키 (accountId)
    @Column(nullable = false, length = 100)
    private String messageKey;

    // Kafka로 전송할 이벤트의 도메인 데이터 (json)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    // Outbox 기록 생성 시각
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Kafka 발행 성공을 기록한 시각 (null 이면 미발행 상태)
    private Instant publishedAt;

    // Kafka 발행 재시도 횟수
    @Column(nullable = false)
    private int retryCount;

    /**
     * Kafka 이벤트 발행을 위한 Outbox 객체를 생성한다.
     * 실제 Kafka 발행은 별도의 발행기가 처리한다.
     * 저장 전에는 eventID를 넣을 수 없어 공통 필드는 발행기가 처리한다.
     *
     * @param aggregateType 이벤트 관련 도메인 종류
     * @param aggregateId   이벤트 관련 도메인 객체 ID
     * @param topic         발행할 Kafka 토픽 이름
     * @param messageKey    발행할 Kafka 메시지 키 (파티션 키)
     * @param payload       전송할 이벤트 데이터 (json)
     * @return 생성된 outbox 객체
     */
    public static Outbox of(String aggregateType, UUID aggregateId, String topic, String messageKey, String payload) {
        Outbox outbox = new Outbox();
        outbox.aggregateType = aggregateType;
        outbox.aggregateId = aggregateId;
        outbox.eventType = topic;
        outbox.topic = topic;
        outbox.messageKey = messageKey;
        outbox.payload = payload;
        return outbox;
    }

    // 발행 완료 처리
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    // 재시도 횟수 증가
    public void increaseRetryCount() {
        this.retryCount++;
    }
}
