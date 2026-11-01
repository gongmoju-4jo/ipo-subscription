package com.gongmoju.allocation.presentation.allocation;

import com.gongmoju.allocation.application.allocation.AllocationResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "청약 1건의 배정 결과")
public record AllocationResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "청약 ID", example = "74b6514c-c1eb-3837-968b-8425f63cb692")
        UUID subscriptionId,
        @Schema(description = "균등 배정 수량", example = "1")
        long equalAllocatedQuantity,
        @Schema(description = "비례 배정 수량", example = "2")
        long proportionalAllocatedQuantity,
        @Schema(description = "배정 수량 (균등 + 비례, 0주 가능)", example = "3")
        long allocatedQuantity,
        @Schema(description = "배정 대금 (배정 수량 x 공모가, 원). 환불액은 청약 상세 조회에서 확인", example = "45000")
        long allocatedAmount
) {

    public static AllocationResponse from(AllocationResult result) {
        return new AllocationResponse(result.ipoId(), result.subscriptionId(), result.equalAllocatedQuantity(),
                result.proportionalAllocatedQuantity(), result.allocatedQuantity(), result.allocatedAmount());
    }
}
