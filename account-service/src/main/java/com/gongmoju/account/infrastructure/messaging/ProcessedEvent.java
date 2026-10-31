package com.gongmoju.account.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka 이벤트의 중복 처리를 방지하기 위해 처리 이력을 기록한다.
 * eventId와 consumerName의 조합을 UNIQUE 제약조건으로 관리한다.
 */
// todo: outbox common으로 이동하기
@Getter
@Entity
@Table(name = "processed_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_event", columnNames = {"event_id", "consumer_name"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "processed_event_id")
    private UUID id;

    // Kafka 이벤트의 고유 ID (발행 측 outbox.id 와 동일)
    @Column(nullable = false)
    private UUID eventId;

    // 이벤트를 처리할 Consumer의 식별 이름
    @Column(nullable = false, length = 100)
    private String consumerName;

    // 발행된 Kafka 이벤트의 토픽 이름
    @Column(nullable = false, length = 100)
    private String topic;

    // 저장된 시각
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    public ProcessedEvent(UUID eventId, String consumerName, String topic) {
        this.eventId = eventId;
        this.consumerName = consumerName;
        this.topic = topic;
    }
}
