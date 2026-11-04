package com.gongmoju.allocation.domain.allocation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 배정 회차 상태 변경: 시작, 실패, 재실행, 완료
class AllocationRoundTest {

    private static final Instant NOW = Instant.parse("2026-11-04T07:01:00Z");

    // 처음 시작하면 RUNNING이고 받은 조건과 시드를 가진다
    @Test
    void startsRunning() {
        UUID ipoId = UUID.randomUUID();

        AllocationRound round = AllocationRound.start(ipoId, 10000, 100, 50, 10, 42L, NOW);

        assertThat(round.getIpoId()).isEqualTo(ipoId);
        assertThat(round.getStatus()).isEqualTo(AllocationRoundStatus.RUNNING);
        assertThat(round.getLotterySeed()).isEqualTo(42L);
        assertThat(round.getStartedAt()).isEqualTo(NOW);
        assertThat(round.getFinishedAt()).isNull();
        assertThat(round.hasSameConditions(10000, 100, 50, 10)).isTrue();
        assertThat(round.hasSameConditions(10000, 100, 50, 20)).isFalse();
    }

    // FAILED 회차는 다시 실행하면 RUNNING이 되고, 시드와 시작 시각은 그대로다
    @Test
    void restartsFailedRound() {
        AllocationRound round = AllocationRound.start(UUID.randomUUID(), 10000, 100, 50, 10, 42L, NOW);
        round.fail(NOW.plusSeconds(5));
        assertThat(round.getStatus()).isEqualTo(AllocationRoundStatus.FAILED);
        assertThat(round.getFinishedAt()).isEqualTo(NOW.plusSeconds(5));

        round.restart();

        assertThat(round.getStatus()).isEqualTo(AllocationRoundStatus.RUNNING);
        assertThat(round.getFinishedAt()).isNull();
        assertThat(round.getLotterySeed()).isEqualTo(42L);
        assertThat(round.getStartedAt()).isEqualTo(NOW);
    }

    // 배정이 끝난(DONE) 회차는 다시 실행하거나 FAILED로 바꿀 수 없다
    @Test
    void rejectsChangingDoneRound() {
        AllocationRound round = AllocationRound.start(UUID.randomUUID(), 10000, 100, 50, 10, 42L, NOW);
        round.finish(NOW.plusSeconds(5));

        assertThatThrownBy(round::restart).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> round.fail(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(round.getStatus()).isEqualTo(AllocationRoundStatus.DONE);
    }
}
