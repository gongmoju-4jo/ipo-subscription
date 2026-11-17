package com.gongmoju.subscription.infrastructure.redis;

import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 사본을 두지 않는다 (ipo.competition-rate.redis-enabled=false). 읽기는 늘 비어 있어 조회마다 DB로 합산한다.
 * Redis 없이 띄우는 테스트용이다.
 */
public class NoOpCompetitionRateCounter implements CompetitionRateCounter {

    @Override
    public void addConfirmed(UUID ipoId, long requestedQuantity) {
        // 사본이 없으니 할 일 없음
    }

    @Override
    public Map<UUID, Long> find(Collection<UUID> ipoIds) {
        return Map.of();
    }

    @Override
    public Set<UUID> startLoading(Collection<UUID> ipoIds) {
        return Set.of();
    }

    @Override
    public void finishLoading(Map<UUID, Long> requestedQuantities) {
        // 사본이 없으니 할 일 없음
    }
}
