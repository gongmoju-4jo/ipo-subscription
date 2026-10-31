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

// 청약 1건당 증거금 기록 1개를 생성한다.
// 증거금률은 100% 고정 (D-19), 정산 시 환불액은 최초 인출액에서 실제 배정 대금을 뺀 금액이다.
// 환불액 = amount - postedAmount

/**
 * 청약별 증거금 인출 기록과 상태를 관리한다.
 */
@Getter
@Entity
@Table(name = "margin", indexes = {
        @Index(name = "ix_margin_ipo_status", columnList = "ipo_id, status"),
        @Index(name = "ix_margin_account_status", columnList = "account_id, status")})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Margin extends BaseEntity {

    // 증거금 고유 ID, margin.reserved의 marginId
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "margin_id")
    private UUID id;

    // 계좌 객체 참조
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // 청약 1건당 증거금 기록은 1건만 생성되도록 제한한다.
    @Column(nullable = false, unique = true)
    private UUID subscriptionId;

    // 공모주 종목 구분
    @Column(nullable = false)
    private UUID ipoId;

    // 처음 인출한 증거금 총액
    @Column(nullable = false)
    private long amount;

    // 정산 완료(Posted) 시 실제 배정받은 주식의 대금을 기록한다.
    private Long postedAmount;

    // 증거금 상태
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarginStatus status = MarginStatus.PENDING;

    // 정산 완료 시각
    private Instant settledAt;

    /**
     * 청약증거금 인출 기록을 생성한다.
     * 실제 잔고 이동 (account.reserveMargin())과 원장 저장은 호출하는 서비스에서 처리한다.
     *
     * @param account        증거금을 인출할 계좌
     * @param subscriptionId 관련 청약 ID
     * @param ipoId          해당 공모주 ID
     * @param amount         인출할 청약증거금 금액
     * @return 증거금 인출 기록
     */
    public static Margin withdraw(Account account, UUID subscriptionId, UUID ipoId, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("증거금은 1 이상이어야 합니다. amount=" + amount);
        }
        Margin margin = new Margin();
        margin.account = account;
        margin.subscriptionId = subscriptionId;
        margin.ipoId = ipoId;
        margin.amount = amount;
        return margin;
    }
}
