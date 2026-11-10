package com.gongmoju.common.event;

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
 * Consumer가 처리한 이벤트의 기록 (중복 수신 방지).
 * <p>
 * 업무 변경과 같은 트랜잭션에서 저장한다. 같은 {@code (eventId, consumerName)}이 이미 있으면 저장하지 않고 건너뛴다.
 * 표(processed_event)는 서비스마다 자기 스키마에 만든다.
 * 저장은 {@link ProcessedEventJpaRepository}의 네이티브 쿼리로만 하며, 이 엔티티로 직접 저장하지 않는다.
 */
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

    // 이벤트 ID (메시지의 eventId)
    @Column(nullable = false)
    private UUID eventId;

    // 이벤트를 처리한 Consumer 이름. 같은 이벤트도 Consumer마다 따로 기록한다
    @Column(nullable = false, length = 100)
    private String consumerName;

    // 이벤트를 받은 토픽
    @Column(nullable = false, length = 100)
    private String topic;

    // 처리한 시각
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant processedAt;
}
