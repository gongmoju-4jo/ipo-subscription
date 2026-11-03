package com.gongmoju.account.domain.margin;

import com.gongmoju.account.domain.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

// 선행 반환 기록 (E-16~E-19). margin이 없는데 반환 요청이 먼저 오면 청약 ID로 남겨 두고, 뒤늦게 온 인출 요청을 막는다
// 수정이 없어 BaseEntity를 상속하지 않고 created_*만 둔다 (ledger_entry 방식). reason CHECK는 schema.sql
// TODO(반환 Consumer 이슈): 생성 메서드와 삽입
@Getter
@Entity
@Table(name = "margin_void_intent")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarginVoidIntent {

    // 청약 ID (논리 참조). 청약 1건에 기록 1개
    @Id
    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    // 처음 받은 반환 사유
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private MarginVoidReason reason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;
}
