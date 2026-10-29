package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.subscription.application.subscription.SubscriptionService;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import com.gongmoju.subscription.presentation.support.RequestHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Subscription", description = "청약 API")
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @Operation(summary = "청약 신청",
            description = "공모주 상태, 청약 기간(Gateway 도착 시각 기준), 수량 범위를 검증한 뒤 subscription.requested를 발행하고 "
                    + "브로커 저장을 확인하면 202를 준다. 202는 접수 완료이며 청약 확정이 아니다. "
                    + "같은 requestId로 다시 요청하면 같은 subscriptionId를 돌려준다.")
    @ApiErrorCodes(
            common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.RESOURCE_NOT_FOUND,
                    CommonErrorCode.INTERNAL_SERVER_ERROR},
            value = {SubscriptionErrorCode.INVALID_STATUS_TRANSITION, SubscriptionErrorCode.SUBSCRIPTION_CLOSED})
    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionAcceptedResponse>> subscribe(
            @Parameter(hidden = true) @RequestHeader(value = RequestHeaders.USER_ID, required = false) String userIdHeader,
            @Parameter(hidden = true) @RequestHeader(value = RequestHeaders.RECEIVED_AT, required = false) String receivedAtHeader,
            @Valid @RequestBody SubscriptionCreateRequest request) {
        UUID userId = RequestHeaders.userId(userIdHeader);
        Instant receivedAt = RequestHeaders.receivedAt(receivedAtHeader);
        SubscriptionAcceptedResponse response = SubscriptionAcceptedResponse.from(
                subscriptionService.subscribe(userId, receivedAt, request.toCommand()));
        return ResponseEntity.accepted().body(ApiResponse.success(response, "청약 신청을 접수했습니다."));
    }
}
