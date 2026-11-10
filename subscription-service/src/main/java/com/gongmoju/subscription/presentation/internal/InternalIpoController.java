package com.gongmoju.subscription.presentation.internal;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.subscription.application.ipo.IpoService;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 게이트웨이 전용 내부 API (API 명세 4.4). 게이트웨이 라우트가 없어 외부에서 호출할 수 없다.
// 게이트웨이가 요청과 무관하게 주기적으로 부르므로 사용자 헤더(X-User-Id)를 읽지 않는다
@Tag(name = "Internal", description = "내부 API (게이트웨이 미노출)")
@RestController
@RequestMapping("/internal/ipos")
@RequiredArgsConstructor
public class InternalIpoController {

    private final IpoService ipoService;

    @Operation(summary = "청약 중 공모주 목록 조회",
            description = "게이트웨이가 접속 대기열 진입 때 청약 중인 공모주인지 확인하려고 주기적으로 받아 간다. "
                    + "상태가 OPEN인 공모주를 모두 준다 (청약 시작 전, 마감 시각이 지났지만 아직 CLOSED가 아닌 공모주 포함). "
                    + "청약 기간 안인지는 호출 쪽이 시작, 마감 시각으로 판단한다. 페이지 없음, 순서는 의미 없음.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR})
    @GetMapping
    public ApiResponse<List<OpenIpoResponse>> listOpen(
            @Parameter(description = "조회할 공모주 상태. OPEN만 허용", example = "OPEN") @RequestParam String status) {
        if (!IpoStatus.OPEN.name().equals(status)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "조회할 수 있는 공모주 상태는 OPEN뿐입니다.", "status");
        }
        return ApiResponse.success(
                ipoService.listByStatus(IpoStatus.OPEN).stream().map(OpenIpoResponse::from).toList(),
                "청약 중인 공모주 목록을 조회했습니다.");
    }
}
