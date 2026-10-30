package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import com.gongmoju.subscription.application.subscription.SubscriptionService;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import com.gongmoju.subscription.presentation.support.PagingValidator;
import com.gongmoju.subscription.presentation.support.RequestHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Subscription", description = "청약 API")
@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private static final int MAX_PAGE_SIZE = 100;

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

    @Operation(summary = "내 청약 목록 조회",
            description = "로그인한 회원의 청약을 거절, 취소된 것까지 모두 조회한다. 정렬은 createdAt만 가능하고 기본은 등록 시각 최신순, "
                    + "같은 시각이면 subscriptionId 순이다. 접수 직후 아직 저장되지 않은 청약은 목록에 없을 수 있다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED})
    @GetMapping
    public ApiResponse<PageResponse<SubscriptionSummaryResponse>> list(
            @Parameter(hidden = true) @RequestHeader(value = RequestHeaders.USER_ID, required = false) String userIdHeader,
            @ParameterObject Pageable pageable,
            @Parameter(hidden = true) @RequestParam(required = false) Integer page,
            @Parameter(hidden = true) @RequestParam(required = false) Integer size) {
        UUID userId = RequestHeaders.userId(userIdHeader);
        PagingValidator.validate(page, size, pageable.getPageSize(), MAX_PAGE_SIZE);
        return ApiResponse.success(
                PageResponse.from(subscriptionService.list(userId, pageable), SubscriptionSummaryResponse::from),
                "청약 목록을 조회했습니다.");
    }

    @Operation(summary = "청약 상세, 상태 조회",
            description = "청약 1건의 상태와 증거금, 배정 수량, 환불 결과를 조회한다. 다른 회원의 청약은 없는 청약과 같은 404다. "
                    + "202 직후 아직 저장되지 않은 청약도 404이므로 접수 직후의 404는 최종 거절로 보지 않고 다시 조회한다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.RESOURCE_NOT_FOUND})
    @GetMapping("/{subscriptionId}")
    public ApiResponse<SubscriptionResponse> get(
            @Parameter(hidden = true) @RequestHeader(value = RequestHeaders.USER_ID, required = false) String userIdHeader,
            @PathVariable UUID subscriptionId) {
        UUID userId = RequestHeaders.userId(userIdHeader);
        return ApiResponse.success(SubscriptionResponse.from(subscriptionService.get(userId, subscriptionId)),
                "청약 상태를 조회했습니다.");
    }
}
