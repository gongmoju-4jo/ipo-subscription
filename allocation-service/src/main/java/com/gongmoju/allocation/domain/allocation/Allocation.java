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

    // 배정 계산에 넣은 청약 수량. 회차의 공모주 조건, lotterySeed와 함께 배정 결과를 다시 계산(재현)할 때 쓴다
    // 이 컬럼을 추가하기 전에 저장한 행은 null이라 재현할 수 없다
    private Long requestedQuantity;

    @Column(nullable = false)
    private long equalAllocatedQuantity;

    @Column(nullable = false)
    private long proportionalAllocatedQuantity;

    // 균등 + 비례
    @Column(nullable = false)
    private long allocatedQuantity;

    @Column(nullable = false)
    private long allocatedAmount;

    /**
     * 배정 계산 결과로 청약 1건의 배정 결과를 만든다 (0주 포함).
     *
     * @param allocationRound 배정 회차
     * @param result          청약 1건의 계산 결과
     * @return 새 배정 결과
     */
    public static Allocation of(AllocationRound allocationRound, AllocationCalculation.SubscriptionAllocation result) {
        Allocation allocation = new Allocation();
        allocation.allocationRound = allocationRound;
        allocation.ipoId = allocationRound.getIpoId();
        allocation.subscriptionId = result.subscription().subscriptionId();
        allocation.userId = result.subscription().userId();
        allocation.accountId = result.subscription().accountId();
        allocation.requestedQuantity = result.subscription().requestedQuantity();
        allocation.equalAllocatedQuantity = result.equalAllocatedQuantity();
        allocation.proportionalAllocatedQuantity = result.proportionalAllocatedQuantity();
        allocation.allocatedQuantity = result.allocatedQuantity();
        allocation.allocatedAmount = result.allocatedAmount();
        return allocation;
    }

    /**
     * 다시 계산한 결과가 저장된 배정 결과와 같은지 확인한다 (균등, 비례, 배정 수량, 배정 대금).
     *
     * @param result 같은 청약을 다시 계산한 결과
     * @return 네 값이 모두 같으면 true
     */
    public boolean hasSameResult(AllocationCalculation.SubscriptionAllocation result) {
        return subscriptionId.equals(result.subscription().subscriptionId())
                && equalAllocatedQuantity == result.equalAllocatedQuantity()
                && proportionalAllocatedQuantity == result.proportionalAllocatedQuantity()
                && allocatedQuantity == result.allocatedQuantity()
                && allocatedAmount == result.allocatedAmount();
    }
}
