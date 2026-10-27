package com.gongmoju.subscription.infrastructure.messaging;

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

// 처리한 이벤트 (중복 수신 방지). 업무 변경과 같은 트랜잭션에서 INSERT, 같은 이벤트가 다시 오면 UNIQUE 위반으로 무시
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

    @Column(nullable = false)
    private UUID eventId;

    @Column(nullable = false, length = 100)
    private String consumerName;

    @Column(nullable = false, length = 100)
    private String topic;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant processedAt;
}
