package com.gongmoju.account.domain.margin;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.Instant;
import java.util.UUID;

// 증거금. 청약 1건당 1개, 증거금률 100% (D-19). 환불액 = amount - postedAmount
@Getter
@Entity
@Table(name = "margin", indexes = {
        @Index(name = "ix_margin_ipo_status", columnList = "ipo_id, status"),
        @Index(name = "ix_margin_account_status", columnList = "account_id, status")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Margin extends BaseEntity {

    // margin.reserved의 marginId
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "margin_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 같은 청약을 두 번 확보하지 않음
    @Column(nullable = false, unique = true)
    private UUID subscriptionId;

    @Column(nullable = false)
    private UUID ipoId;

    @Column(nullable = false)
    private long amount;

    // 정산 금액 = 배정 대금. POSTED일 때만
    private Long postedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarginStatus status = MarginStatus.PENDING;

    private Instant settledAt;
}
