package com.gongmoju.account.presentation.auth;

import com.gongmoju.account.application.auth.AuthService;
import com.gongmoju.account.global.exception.AccountErrorCode;
import com.gongmoju.account.global.swagger.ApiErrorCodes;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "로그인", description = "인증 없이 호출한다. 로그인 ID가 없는 경우와 비밀번호가 틀린 경우는 같은 401로 응답한다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR}, value = {AccountErrorCode.INVALID_CREDENTIALS})
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(
                LoginResponse.from(authService.login(request.loginId(), request.password())), "로그인했습니다.");
    }
}
