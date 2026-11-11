package com.gongmoju.subscription.presentation.internal;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import com.gongmoju.subscription.application.subscription.SubscriptionService;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import com.gongmoju.subscription.presentation.support.PagingValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 배정 서비스 전용 내부 API (API 명세 4.1). 게이트웨이 라우트가 없어 외부에서 호출할 수 없다.
// 배정 서비스가 ipo.closed Consumer에서 호출해 사용자 헤더가 전달되지 않으므로 X-User-Id를 읽지 않는다
@Tag(name = "Internal", description = "내부 API (배정 서비스 전용, 게이트웨이 미노출)")
@RestController
@RequestMapping("/internal/ipos")
@RequiredArgsConstructor
public class InternalSubscriptionController {

    // 배정용 대량 조회라 외부 목록 API(기본 20, 최대 100)와 다르다. 공통 Pageable은 100으로 줄이므로 쓰지 않는다
    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 1000;

    private final SubscriptionService subscriptionService;

    // page 방식. 배정 서비스가 키셋으로 바뀌면(#135) 지운다 (#138). page를 보내면 이 메서드, 없으면 아래 키셋 메서드가 받는다
    @Operation(summary = "공모주별 확정 청약 목록 조회 (page 방식)",
            description = "배정 서비스가 ipo.closed를 받은 뒤 확정(CONFIRMED) 청약을 페이지 단위로 모두 가져간다. "
                    + "공모주가 마감(CLOSED)된 뒤에만 조회할 수 있어서 조회하는 동안 목록이 바뀌지 않는다. "
                    + "정렬은 subscriptionId 오름차순 고정이며 sort 파라미터는 무시한다. "
                    + "page를 보내면 page 방식, 보내지 않으면 키셋 방식으로 응답한다. 키셋 방식으로 바뀐 뒤 이 방식은 없어진다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.RESOURCE_NOT_FOUND},
            value = {SubscriptionErrorCode.INVALID_STATUS_TRANSITION})
    @GetMapping(value = "/{ipoId}/subscriptions", params = "page")
    public ApiResponse<PageResponse<ConfirmedSubscriptionResponse>> listConfirmed(
            @PathVariable UUID ipoId,
            @Parameter(description = "조회할 청약 상태. CONFIRMED만 허용", example = "CONFIRMED") @RequestParam String status,
            @Parameter(description = "페이지 번호 (0부터)", example = "0") @RequestParam(required = false) Integer page,
            @Parameter(description = "페이지 크기. 기본 500, 최대 1000", example = "500") @RequestParam(required = false) Integer size,
            @Parameter(description = "키셋 방식 전용. page와 함께 보내면 400") @RequestParam(required = false) UUID afterSubscriptionId) {
        validateStatus(status);
        if (afterSubscriptionId != null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "page와 afterSubscriptionId는 함께 보낼 수 없습니다.", "afterSubscriptionId");
        }
        int appliedSize = appliedSize(size);
        PagingValidator.validate(page, size, appliedSize, MAX_PAGE_SIZE);

        return ApiResponse.success(
                PageResponse.from(subscriptionService.listConfirmed(ipoId, page == null ? 0 : page, appliedSize),
                        ConfirmedSubscriptionResponse::from),
                "목록을 조회했습니다.");
    }

    @Operation(summary = "공모주별 확정 청약 목록 조회 (키셋 방식)",
            description = "배정 서비스가 ipo.closed를 받은 뒤 확정(CONFIRMED) 청약을 subscriptionId 오름차순으로 나눠 모두 가져간다. "
                    + "첫 요청은 afterSubscriptionId 없이 보내고, 다음 요청부터 직전 응답의 마지막 subscriptionId를 넣는다. "
                    + "hasNext가 false면 끝이다. totalElements는 첫 요청에만 있다. "
                    + "공모주가 마감(CLOSED)된 뒤에만 조회할 수 있어서 조회하는 동안 목록이 바뀌지 않는다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.RESOURCE_NOT_FOUND},
            value = {SubscriptionErrorCode.INVALID_STATUS_TRANSITION})
    @GetMapping(value = "/{ipoId}/subscriptions", params = "!page")
    public ApiResponse<ConfirmedSubscriptionKeysetResponse> listConfirmedAfter(
            @PathVariable UUID ipoId,
            @Parameter(description = "조회할 청약 상태. CONFIRMED만 허용", example = "CONFIRMED") @RequestParam String status,
            @Parameter(description = "직전 응답의 마지막 subscriptionId. 첫 요청은 보내지 않음")
            @RequestParam(required = false) UUID afterSubscriptionId,
            @Parameter(description = "페이지 크기. 기본 500, 최대 1000", example = "500") @RequestParam(required = false) Integer size) {
        validateStatus(status);
        int appliedSize = appliedSize(size);
        PagingValidator.validate(null, size, appliedSize, MAX_PAGE_SIZE);

        return ApiResponse.success(
                ConfirmedSubscriptionKeysetResponse.from(
                        subscriptionService.listConfirmedAfter(ipoId, afterSubscriptionId, appliedSize), appliedSize),
                "목록을 조회했습니다.");
    }

    private static void validateStatus(String status) {
        if (!SubscriptionStatus.CONFIRMED.name().equals(status)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "조회할 수 있는 청약 상태는 CONFIRMED뿐입니다.", "status");
        }
    }

    // 적용할 페이지 크기. 범위 밖 size는 PagingValidator에서 400이 되므로 기본값으로 둔다 (page 상한 계산에서 0으로 나누지 않게)
    private static int appliedSize(Integer size) {
        return (size == null || size < 1 || size > MAX_PAGE_SIZE) ? DEFAULT_PAGE_SIZE : size;
    }
}
