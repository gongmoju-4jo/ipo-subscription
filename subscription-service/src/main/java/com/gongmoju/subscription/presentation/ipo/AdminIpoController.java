package com.gongmoju.subscription.presentation.ipo;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.subscription.application.ipo.IpoService;
import com.gongmoju.subscription.global.swagger.ApiErrorCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자 전용. X-User-Role 확인(없거나 ADMIN이 아니면 403)은 본문 검사보다 먼저 돌도록 AdminRoleInterceptor가 한다
@Tag(name = "Ipo Admin", description = "공모주 관리자 API")
@RestController
@RequestMapping("/api/v1/admin/ipos")
@RequiredArgsConstructor
public class AdminIpoController {

    private final IpoService ipoService;

    @Operation(summary = "공모주 등록",
            description = "공모주를 OPEN 상태로 등록한다. 최소 청약 수량은 없으면 10, 증거금률은 없으면 1.00이고 1.00만 받는다. "
                    + "청약 마감 시각이 이미 지났으면 400이고, 시작 시각이 지난 것은 시연에서 바로 청약 중으로 등록하려고 받는다. "
                    + "물량, 한도와 공모가를 곱한 금액이 계산 범위(long)를 넘으면 400이다. "
                    + "등록한 공모주는 게이트웨이의 청약 중 공모주 목록 갱신(최대 5초) 뒤부터 접속 대기열에 들어갈 수 있다.")
    // ResponseEntity로 201을 주면 Swagger가 상태를 알 수 없어 200으로 표시하므로 성공 응답을 직접 적는다
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록됨")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.FORBIDDEN})
    @PostMapping
    public ResponseEntity<ApiResponse<IpoCreateResponse>> register(@Valid @RequestBody IpoCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(IpoCreateResponse.from(ipoService.register(request.toCommand())),
                        "공모주가 등록되었습니다."));
    }
}
