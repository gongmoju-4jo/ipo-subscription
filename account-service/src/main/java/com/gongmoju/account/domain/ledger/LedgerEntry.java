package com.gongmoju.account.domain.ledger;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.margin.Margin;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

// 원장. 추가만 하고 수정, 삭제하지 않음 (정정은 반대 거래 추가). 그래서 BaseEntity 대신 created_at, created_by만 둔다
// CHECK(amount > 0, 거래 후 잔고 0 이상, DEPOSIT이면 request_id 필수)는 schema.sql에 둔다
@Getter
@Entity
@Table(name = "ledger_entry",
        indexes = @Index(name = "ix_ledger_entry_account_created", columnList = "account_id, created_at DESC"))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ledger_entry_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private LedgerEntryType type;

    // 항상 양수, 방향은 type으로 판단
    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long cashBalanceAfter;

    @Column(nullable = false, updatable = false)
    private long marginBalanceAfter;

    // DEPOSIT이면 NULL
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "margin_id", updatable = false)
    private Margin margin;

    // 청약 ID (논리 참조). DEPOSIT이면 NULL
    @Column(updatable = false)
    private UUID subscriptionId;

    // 가상 입금 요청 ID (멱등키). DEPOSIT일 때만
    @Column(unique = true, updatable = false)
    private UUID requestId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // 돈을 움직인 주체 (회원 ID 또는 SYSTEM:account-service)
    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;
}
