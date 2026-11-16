package com.gongmoju.account.presentation.user;

import com.gongmoju.account.application.user.UserService;
import com.gongmoju.account.global.swagger.ApiErrorCodes;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
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
@Tag(name = "User Admin", description = "회원 관리자 API")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "관리자 계정 생성",
            description = "관리자만 호출한다. 회원가입과 같은 항목을 받아 역할이 ADMIN인 계정을 만든다. "
                    + "계좌는 만들지 않는다. 만든 관리자는 created_by에 남는다.")
    // ResponseEntity로 201을 주면 Swagger가 상태를 알 수 없어 200으로 표시하므로 성공 응답을 직접 적는다
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성됨")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.FORBIDDEN,
            CommonErrorCode.DUPLICATE_RESOURCE})
    @PostMapping
    public ResponseEntity<ApiResponse<AdminUserCreateResponse>> create(@Valid @RequestBody AdminUserCreateRequest request) {
        AdminUserCreateResponse response = AdminUserCreateResponse.from(
                userService.createAdmin(request.loginId(), request.password(), request.name()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "관리자 계정을 만들었습니다."));
    }
}
