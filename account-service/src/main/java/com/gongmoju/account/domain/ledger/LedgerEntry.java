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

// CHECK(amount > 0, 거래 후 잔고 0 이상, DEPOSIT이면 request_id 필수)는 schema.sql에 둔다

/**
 * 계좌의 모든 잔고 변경 내역을 기록하는 원장 엔티티.
 *
 * 원장 기록은 생성 후 수정하거나 삭제하지 않으며,
 * 거래 정정이 필요한 경우 반대 거래를 추가하여 처리한다.
 * 거래 금액과 거래 후 잔고는 DB의 CHECK 제약조건으로 검증한다.
 */
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

    // 거래종류
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private LedgerEntryType type;

    // 거래 금액: 항상 양수, 방향은 type으로 판단
    @Column(nullable = false, updatable = false)
    private long amount;

    // 거래 후 남은 예수금
    @Column(nullable = false, updatable = false)
    private long cashBalanceAfter;

    // 거래 후 남은 증거금
    @Column(nullable = false, updatable = false)
    private long marginBalanceAfter;

    // 증거금: DEPOSIT(입금)이면 NULL
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "margin_id", updatable = false)
    private Margin margin;

    // 청약 ID (논리 참조). DEPOSIT이면 NULL
    @Column(updatable = false)
    private UUID subscriptionId;

    // 입금 중복 처리 방지용 요청 ID (멱등키)
    @Column(unique = true, updatable = false)
    private UUID requestId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // 돈을 움직인 주체 (회원 ID 또는 SYSTEM:account-service)
    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;

    // 원장 기록 생성 시 거래 금액을 검증하고, 잔고 변경 직후의 계좌 잔액을 기록한다.
    private LedgerEntry(Account account, LedgerEntryType type, long amount, Margin margin, UUID subscriptionId, UUID requestId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("원장 금액은 1 이상이어야 합니다. amount=" + amount);
        }
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.cashBalanceAfter = account.getCashBalance();
        this.marginBalanceAfter = account.getMarginBalance();
        this.margin = margin;
        this.subscriptionId = subscriptionId;
        this.requestId = requestId;
    }

    // DEPOSIT: 가상 입금 거래의 원장 기록 생성
    // requestId로 입금 요청 식별, 중복 기록은 DB UNIQUE 제약으로 방지
    public static LedgerEntry deposit(Account account, long amount, UUID requestId) {
        if (requestId == null) {
            throw new IllegalArgumentException("입금은 requestId가 필요합니다.");
        }
        return new LedgerEntry(account, LedgerEntryType.DEPOSIT, amount, null, null, requestId);
    }

    // 증거금 확보, 정산, 환불, 해제 거래의 원장 기록 생성
    // Margin 객체를 연결하고, 해당 Margin의 subscriptionId를 원장에 기록
    public static LedgerEntry ofMargin(Account account, LedgerEntryType type, long amount, Margin margin) {
        if (type == LedgerEntryType.DEPOSIT) {
            throw new IllegalArgumentException("입금은 deposit()으로 기록합니다.");
        }
        if (margin == null) {
            throw new IllegalArgumentException("증거금 거래는 margin이 필요합니다. type=" + type);
        }
        return new LedgerEntry(account, type, amount, margin, margin.getSubscriptionId(), null);
    }
}
