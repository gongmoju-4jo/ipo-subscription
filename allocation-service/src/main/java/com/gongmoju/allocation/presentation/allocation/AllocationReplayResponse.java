package com.gongmoju.allocation.presentation.allocation;

import com.gongmoju.allocation.application.allocation.AllocationReplayResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "추첨 재현 결과. 저장된 공모주 조건, 추첨 시드, 청약 수량으로 배정을 다시 계산해 저장된 배정 결과와 비교한 값")
public record AllocationReplayResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "모든 청약의 균등, 비례, 배정 수량, 배정 대금이 같으면 true", example = "true")
        boolean matched,
        @Schema(description = "비교한 청약 수", example = "3")
        int comparedCount,
        @Schema(description = "결과가 다른 청약 ID (subscriptionId 순). 일치하면 빈 배열", example = "[]")
        List<UUID> mismatchedSubscriptionIds
) {

    public static AllocationReplayResponse from(AllocationReplayResult result) {
        return new AllocationReplayResponse(result.ipoId(), result.matched(), result.comparedCount(),
                result.mismatchedSubscriptionIds());
    }
}
