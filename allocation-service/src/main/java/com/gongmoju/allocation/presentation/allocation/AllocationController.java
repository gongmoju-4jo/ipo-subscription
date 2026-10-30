package com.gongmoju.allocation.presentation.allocation;

import com.gongmoju.allocation.application.allocation.AllocationService;
import com.gongmoju.allocation.global.swagger.ApiErrorCodes;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 인증(JWT 검증, 401)은 Gateway가 처리하고, 요청자 회원 ID는 Gateway가 넣은 X-User-Id로 받는다
@Tag(name = "Allocation", description = "배정 API")
@RestController
@RequestMapping("/api/v1/allocations")
@RequiredArgsConstructor
public class AllocationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final AllocationService allocationService;

    @Operation(summary = "배정 결과 조회",
            description = "본인 청약 1건의 균등, 비례 배정 수량과 배정 대금을 조회한다. "
                    + "배정 전, 배정 대상이 아닌 청약(거절, 취소), 본인 청약이 아니면 모두 404다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.RESOURCE_NOT_FOUND})
    @GetMapping("/subscriptions/{subscriptionId}")
    public ApiResponse<AllocationResponse> get(
            @Parameter(hidden = true) @RequestHeader(value = USER_ID_HEADER, required = false) String userIdHeader,
            @PathVariable UUID subscriptionId) {
        UUID userId = userId(userIdHeader);
        return ApiResponse.success(AllocationResponse.from(allocationService.get(subscriptionId, userId)),
                "배정 결과를 조회했습니다.");
    }

    // 헤더가 없거나 UUID가 아니면 Gateway를 거치지 않은 요청으로 보고 401로 응답한다 (청약 서비스 RequestHeaders와 같은 규칙)
    private UUID userId(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
