package com.gongmoju.subscription.domain.ipo;

import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 공모주. CHECK 제약(1 이상, 균등 물량 범위, 증거금률 범위, 기간)은 ERD_v1.1_DDL.sql 기준으로 schema.sql 또는 생성 로직에서 검증
@Getter
@Entity
@Table(name = "ipo")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ipo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ipo_id")
    private UUID id;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false)
    private long offerPrice;

    @Column(nullable = false)
    private long retailQuantity;

    @Column(nullable = false)
    private long equalQuantity;

    @Column(nullable = false)
    private long minQuantity = 10;

    @Column(nullable = false)
    private long maxQuantity;

    // 증거금률 100% (D-19)
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal marginRate = BigDecimal.ONE;

    @Column(nullable = false)
    private Instant subscriptionStartAt;

    @Column(nullable = false)
    private Instant subscriptionEndAt;

    // 화면 표시용 (MVP는 배정 직후 자동 환불)
    @Column(nullable = false)
    private LocalDate refundDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IpoStatus status = IpoStatus.OPEN;

    @Version
    @Column(nullable = false)
    private long version;
}
