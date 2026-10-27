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
}
