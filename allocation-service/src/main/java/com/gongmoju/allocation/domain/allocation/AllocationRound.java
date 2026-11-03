package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// 배정 실행. 공모주당 한 번 (ipo_id UNIQUE로 중복 실행 방지)
// 공모주 조건은 ipo.closed로 받은 값을 그대로 저장해서 같은 조건과 lotterySeed로 결과를 재현할 수 있게 한다
@Getter
@Entity
@Table(name = "allocation_round")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllocationRound extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "allocation_round_id")
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID ipoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AllocationRoundStatus status;

    @Column(nullable = false)
    private long offerPrice;

    @Column(nullable = false)
    private long retailQuantity;

    @Column(nullable = false)
    private long equalQuantity;

    @Column(nullable = false)
    private long minQuantity;

    private Long lotterySeed;

    private Instant startedAt;

    private Instant finishedAt;

    private AllocationRound(UUID ipoId, long offerPrice, long retailQuantity, long equalQuantity, long minQuantity,
                            long lotterySeed, Instant startedAt) {
        this.ipoId = ipoId;
        this.status = AllocationRoundStatus.RUNNING;
        this.offerPrice = offerPrice;
        this.retailQuantity = retailQuantity;
        this.equalQuantity = equalQuantity;
        this.minQuantity = minQuantity;
        this.lotterySeed = lotterySeed;
        this.startedAt = startedAt;
    }

    // ipo.closed를 처음 받았을 때 RUNNING으로 만든다. 시드는 이때 한 번 정하고 재실행해도 바꾸지 않는다
    public static AllocationRound start(UUID ipoId, long offerPrice, long retailQuantity, long equalQuantity,
                                        long minQuantity, long lotterySeed, Instant startedAt) {
        return new AllocationRound(ipoId, offerPrice, retailQuantity, equalQuantity, minQuantity, lotterySeed, startedAt);
    }

    // 실패했거나(FAILED) 처리 중에 멈춘(RUNNING) 회차를 다시 실행한다. 저장한 조건과 시드는 그대로 쓴다
    public void restart() {
        if (status == AllocationRoundStatus.DONE) {
            throw new IllegalStateException("이미 배정이 끝난 회차입니다. allocationRoundId=" + id);
        }
        this.status = AllocationRoundStatus.RUNNING;
        this.finishedAt = null;
    }

    // 배정을 끝내지 못했을 때 기록한다. 같은 ipo.closed가 재시도되거나 다시 오면 restart()로 다시 실행한다
    public void fail(Instant failedAt) {
        if (status == AllocationRoundStatus.DONE) {
            throw new IllegalStateException("이미 배정이 끝난 회차입니다. allocationRoundId=" + id);
        }
        this.status = AllocationRoundStatus.FAILED;
        this.finishedAt = failedAt;
    }

    // 다시 받은 ipo.closed의 공모주 조건이 처음 저장한 조건과 같은지 확인한다
    public boolean hasSameConditions(long offerPrice, long retailQuantity, long equalQuantity, long minQuantity) {
        return this.offerPrice == offerPrice && this.retailQuantity == retailQuantity
                && this.equalQuantity == equalQuantity && this.minQuantity == minQuantity;
    }

    // 배정 결과를 저장하기 전에 확인한다. RUNNING이 아니면 이미 끝났거나 실패한 회차라 다시 배정하지 않는다
    public void checkRunning() {
        if (status != AllocationRoundStatus.RUNNING) {
            throw new IllegalStateException("배정 실행 중인 회차가 아닙니다. allocationRoundId=" + id + ", status=" + status);
        }
        if (lotterySeed == null) {
            throw new IllegalStateException("추첨 시드가 없습니다. allocationRoundId=" + id);
        }
    }

    // 배정 결과 저장과 같은 트랜잭션에서 DONE으로 바꾼다
    public void finish(Instant finishedAt) {
        checkRunning();
        this.status = AllocationRoundStatus.DONE;
        this.finishedAt = finishedAt;
    }
}
