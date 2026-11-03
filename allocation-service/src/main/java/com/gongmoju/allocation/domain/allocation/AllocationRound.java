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
