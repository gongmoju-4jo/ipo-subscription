package com.gongmoju.allocation.presentation.allocation;

import com.gongmoju.allocation.application.allocation.AllocationReplayService;
import com.gongmoju.allocation.global.swagger.ApiErrorCodes;
import com.gongmoju.common.auth.UserRole;
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

// 관리자 전용. Gateway가 /api/v1/admin/**을 ADMIN만 통과시키지만, 서비스 포트로 직접 호출하면 Gateway를 거치지 않으므로
// Gateway가 넣은 X-User-Role도 한 번 더 확인한다 (헤더가 없거나 ADMIN이 아니면 403)
@Tag(name = "Allocation Admin", description = "배정 관리자 API")
@RestController
@RequestMapping("/api/v1/admin/allocations")
@RequiredArgsConstructor
public class AllocationReplayController {

    private static final String USER_ROLE_HEADER = "X-User-Role";

    private final AllocationReplayService allocationReplayService;

    @Operation(summary = "추첨 재현",
            description = "배정이 끝난 공모주를 저장된 공모주 조건, 추첨 시드, 청약 수량으로 다시 계산해 저장된 배정 결과와 청약마다 비교한다. "
                    + "읽기만 하고 아무것도 바꾸지 않는다. 배정 회차가 없으면 404, 배정이 끝나지 않았거나 청약 수량이 저장되지 않은 배정 결과가 있으면 422다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.FORBIDDEN,
            CommonErrorCode.RESOURCE_NOT_FOUND, CommonErrorCode.BUSINESS_RULE_VIOLATION})
    @GetMapping("/ipos/{ipoId}/replay")
    public ApiResponse<AllocationReplayResponse> replay(
            @Parameter(hidden = true) @RequestHeader(value = USER_ROLE_HEADER, required = false) String userRoleHeader,
            @PathVariable UUID ipoId) {
        if (UserRole.from(userRoleHeader) != UserRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return ApiResponse.success(AllocationReplayResponse.from(allocationReplayService.replay(ipoId)),
                "추첨 재현 결과를 조회했습니다.");
    }
}
