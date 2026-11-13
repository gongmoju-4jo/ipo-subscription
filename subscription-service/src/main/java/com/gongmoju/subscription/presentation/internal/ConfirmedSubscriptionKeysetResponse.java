package com.gongmoju.subscription.presentation.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gongmoju.subscription.application.subscription.ConfirmedSubscriptionKeysetResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "배정용 확정 청약 키셋 목록 (page 없이 요청할 때)")
public record ConfirmedSubscriptionKeysetResponse(
        @Schema(description = "확정 청약. subscriptionId 오름차순")
        List<ConfirmedSubscriptionResponse> items,
        @Schema(description = "적용한 페이지 크기", example = "500")
        int size,
        @Schema(description = "다음 목록이 있는지. true면 마지막 항목의 subscriptionId를 afterSubscriptionId로 다시 요청", example = "true")
        boolean hasNext,
        @Schema(description = "확정 청약 전체 건수. 첫 요청(afterSubscriptionId 없음)에만 있음", example = "200000", nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long totalElements
) {

    public static ConfirmedSubscriptionKeysetResponse from(ConfirmedSubscriptionKeysetResult result, int size) {
        return new ConfirmedSubscriptionKeysetResponse(
                result.items().stream().map(ConfirmedSubscriptionResponse::from).toList(),
                size, result.hasNext(), result.totalElements());
    }
}
