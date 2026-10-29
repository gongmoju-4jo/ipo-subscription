package com.gongmoju.account.presentation.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 로그인은 형식(길이, 문자)을 검사하지 않는다. 틀리면 어떤 이유든 같은 401이고, 가입 규칙을 응답으로 알려주지 않는다
@Schema(description = "로그인 요청")
public record LoginRequest(
        @Schema(description = "로그인 ID", example = "investor01")
        @NotBlank(message = "필수값입니다.")
        String loginId,
        @Schema(description = "비밀번호", example = "password1234")
        @NotBlank(message = "필수값입니다.")
        String password
) {

    // record 기본 toString은 모든 필드를 찍으므로, 비밀번호가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "LoginRequest[loginId=" + loginId + ", password=****]";
    }
}
