package com.gongmoju.subscription.presentation.ipo;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import com.gongmoju.subscription.application.ipo.IpoService;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import com.gongmoju.subscription.presentation.support.PagingValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 인증(JWT 검증, 401)은 Gateway가 처리한다. 공모주 조회는 회원 ID를 쓰지 않아 X-User-Id를 읽지 않는다
@Tag(name = "Ipo", description = "공모주 API")
@RestController
@RequestMapping("/api/v1/ipos")
@RequiredArgsConstructor
public class IpoController {

    private static final int MAX_PAGE_SIZE = 100;

    private final IpoService ipoService;

    @Operation(summary = "공모주 목록 조회",
            description = "청약 시작 전, 진행 중, 마감된 종목을 모두 조회한다. 정렬은 createdAt만 가능하고 기본은 등록 시각 최신순, "
                    + "같은 시각이면 ipoId 순이다. 공모주가 없으면 빈 목록이다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED})
    @GetMapping
    public ApiResponse<PageResponse<IpoSummaryResponse>> list(
            @ParameterObject Pageable pageable,
            @Parameter(hidden = true) @RequestParam(required = false) Integer page,
            @Parameter(hidden = true) @RequestParam(required = false) Integer size) {
        PagingValidator.validate(page, size, pageable.getPageSize(), MAX_PAGE_SIZE);
        return ApiResponse.success(
                PageResponse.from(ipoService.list(pageable), IpoSummaryResponse::from),
                "공모주 목록을 조회했습니다.");
    }

    @Operation(summary = "공모주 상세 조회", description = "공모주 정보와 청약 조건을 조회한다. 청약 가능 여부는 상태와 청약 시작, 마감 시각을 함께 확인한다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.RESOURCE_NOT_FOUND})
    @GetMapping("/{ipoId}")
    public ApiResponse<IpoResponse> get(@PathVariable UUID ipoId) {
        return ApiResponse.success(IpoResponse.from(ipoService.get(ipoId)), "공모주를 조회했습니다.");
    }
}
