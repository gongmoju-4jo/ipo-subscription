package com.gongmoju.common.outbox;

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

/**
 * 발행 대기 중인 이벤트 (Transactional Outbox).
 * <p>
 * 업무 변경과 같은 트랜잭션에서 저장하고, 발행기({@link OutboxPublisher})가 나중에 Kafka로 보낸다.
 * 서비스 3개가 이 클래스를 같이 쓰며, 표(outbox)는 서비스마다 자기 스키마에 만든다 (ERD v1.1 공통 outbox).
 * 체크 제약과 미발행 부분 인덱스(WHERE published_at IS NULL)는 서비스의 schema.sql에 둔다.
 */
@Getter
@Entity
@Table(name = "outbox")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox {

    // 메시지의 eventId로 그대로 쓴다 (저장할 때 정해진다)
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "outbox_id")
    private UUID id;

    // 이벤트가 나온 도메인 종류 (MARGIN, ALLOCATION, SUBSCRIPTION, IPO). 저장과 추적용이고 메시지에는 싣지 않는다
    // TODO: enum 전환 여부는 별도 이슈에서 논의 (서비스별 enum 제안)
    @Column(nullable = false, length = 30)
    private String aggregateType;

    // 이벤트와 관련된 도메인 객체의 ID (예: marginId, subscriptionId)
    @Column(nullable = false)
    private UUID aggregateId;

    // 발생한 사건의 종류. 지금은 토픽 이름과 같은 값을 쓴다 (예: margin.reserved)
    @Column(nullable = false, length = 50)
    private String eventType;

    // 발행할 Kafka 토픽
    @Column(nullable = false, length = 100)
    private String topic;

    // Kafka 메시지 키. 같은 키는 같은 파티션으로 가서 순서가 지켜진다
    @Column(nullable = false, length = 100)
    private String messageKey;

    // 도메인 필드만 담은 JSON. 공통 필드(eventId, eventType, occurredAt)는 발행할 때 붙는다
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    // 저장 시각. 메시지의 occurredAt이 된다
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Kafka 발행을 마친 시각. null이면 미발행
    private Instant publishedAt;

    // 발행에 실패한 횟수
    @Column(nullable = false)
    private int retryCount;

    /**
     * 발행 대기 행을 만든다. Kafka로 보내는 일은 발행기가 따로 한다.
     * <p>
     * payload에는 도메인 필드만 담는다. 공통 필드(eventId = outbox_id, eventType, occurredAt = created_at)는 발행기가 붙인다.
     * outbox_id가 저장할 때 정해져서, 저장 전에 만드는 payload에는 eventId를 넣을 수 없기 때문이다.
     *
     * @param aggregateType 이벤트가 나온 도메인 종류
     * @param aggregateId   이벤트와 관련된 도메인 객체의 ID
     * @param topic         발행할 Kafka 토픽 (eventType도 이 값으로 정해진다)
     * @param messageKey    Kafka 메시지 키 (파티션 결정)
     * @param payload       도메인 필드만 담은 JSON 문자열
     * @return 저장 전의 Outbox
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

    /**
     * 발행을 마쳤다고 표시한다.
     *
     * @param publishedAt 발행이 확인된 시각
     */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    /**
     * 발행 실패 횟수를 1 올린다.
     */
    public void increaseRetryCount() {
        this.retryCount++;
    }
}
