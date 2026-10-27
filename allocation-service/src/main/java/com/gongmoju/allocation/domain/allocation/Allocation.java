package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 청약별 배정 결과. 청약 미달이 아니면 공모주별 allocatedQuantity 합 = retailQuantity
@Getter
@Entity
@Table(name = "allocation")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Allocation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "allocation_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocation_round_id", nullable = false)
    private AllocationRound allocationRound;

    @Column(nullable = false)
    private UUID ipoId;

    @Column(nullable = false, unique = true)
    private UUID subscriptionId;

    // 배정 결과 조회 본인 확인 (JWT sub와 비교)
    @Column(nullable = false)
    private UUID userId;

    // allocation.result의 파티션 키
    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private long equalAllocatedQuantity;

    @Column(nullable = false)
    private long proportionalAllocatedQuantity;

    // 균등 + 비례
    @Column(nullable = false)
    private long allocatedQuantity;

    @Column(nullable = false)
    private long allocatedAmount;
}
