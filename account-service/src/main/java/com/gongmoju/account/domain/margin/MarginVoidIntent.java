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

/**
 * 청약별 증거금 반환 요청 기록.
 * <p>
 * 인출보다 반환 요청이 먼저 도착한 경우에도 기록한다.
 * 인출 처리 시 해당 기록을 조회하여 뒤늦은 증거금 인출을 방지한다.
 * <p>
 * 생성 후 수정하지 않으므로 BaseEntity를 상속하지 않는다.
 */
// TODO(반환 Consumer 이슈): 삽입
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

    // 반환 요청한 계좌 객체
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

    // 기록 주체. Consumer가 저장하므로 SYSTEM: account-service
    @CreatedBy
    @Column(nullable = false, updatable = false, length = 100)
    private String createdBy;

    /**
     * 증거금 반환 요청 기록 객체를 생성한다.
     * <p>
     * 호출 전에 계좌 행 락을 획득해야 하고,
     * 실제 DB 저장은 Repository에서 수행한다.
     *
     * @param account        요청 계좌
     * @param subscriptionId 청약 ID
     * @param reason         반환 요청 사유
     * @return 생성된 반환 요청 기록 객체
     */
    public static MarginVoidIntent record(Account account, UUID subscriptionId, MarginVoidReason reason) {
        MarginVoidIntent intent = new MarginVoidIntent();
        intent.account = account;
        intent.subscriptionId = subscriptionId;
        intent.reason = reason;
        return intent;
    }
}
