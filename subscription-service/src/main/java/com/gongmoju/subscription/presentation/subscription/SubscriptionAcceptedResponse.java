package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.subscription.application.subscription.SubscribeResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "청약 신청 접수 결과 (확정 아님)")
public record SubscriptionAcceptedResponse(
        @Schema(description = "API가 생성한 청약 ID", example = "e8bd1887-fe8e-378c-8f04-13d9d406e984")
        UUID subscriptionId,
        @Schema(description = "클라이언트 멱등 요청 ID", example = "3b8f0000-0000-4000-8000-000000000031")
        UUID requestId,
        // 응답용 상태다. DB 청약 상태(SubscriptionStatus)가 아니며 접수 Consumer가 처리하면 PENDING 청약이 생긴다
        @Schema(description = "ACCEPTED (Kafka 접수 완료). DB의 청약 상태값이 아니다", example = "ACCEPTED")
        String status,
        @Schema(description = "청약 상태 조회 API 경로",
                example = "/api/v1/subscriptions/e8bd1887-fe8e-378c-8f04-13d9d406e984")
        String statusUrl
) {

    private static final String ACCEPTED = "ACCEPTED";
    private static final String STATUS_URL_PREFIX = "/api/v1/subscriptions/";

    public static SubscriptionAcceptedResponse from(SubscribeResult result) {
        return new SubscriptionAcceptedResponse(result.subscriptionId(), result.requestId(), ACCEPTED,
                STATUS_URL_PREFIX + result.subscriptionId());
    }
}
