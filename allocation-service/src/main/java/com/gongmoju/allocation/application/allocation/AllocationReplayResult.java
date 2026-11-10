package com.gongmoju.allocation.application.allocation;

import java.util.List;
import java.util.UUID;

/**
 * 추첨 재현 결과. 저장된 공모주 조건, lotterySeed, 청약 수량으로 배정을 다시 계산해 저장된 배정 결과와 비교한 값이다.
 *
 * @param ipoId                     공모주 ID
 * @param matched                   모든 청약의 결과가 같으면 true
 * @param comparedCount             비교한 청약 수
 * @param mismatchedSubscriptionIds 결과가 다른 청약 ID (subscriptionId 순)
 */
public record AllocationReplayResult(
        UUID ipoId,
        boolean matched,
        int comparedCount,
        List<UUID> mismatchedSubscriptionIds
) {
}
