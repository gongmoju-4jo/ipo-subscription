package com.gongmoju.account.presentation.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청. 역할(role)은 받지 않고 가입자는 항상 USER")
public record SignUpRequest(
        @Schema(description = "로그인 ID (4~20자, 영문 소문자, 숫자)", example = "investor01")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = "^[a-z0-9]{4,20}$", message = "4~20자의 영문 소문자, 숫자만 사용할 수 있습니다.")
        String loginId,
        @Schema(description = "비밀번호 (8자 이상 20자 이하)", example = "password1234")
        @NotBlank(message = "필수값입니다.")
        @Size(min = 8, max = 20, message = "8자 이상 20자 이하여야 합니다.")
        String password,
        @Schema(description = "이름 (1~20자)", example = "홍길동")
        @NotBlank(message = "필수값입니다.")
        @Size(max = 20, message = "20자 이하여야 합니다.")
        String name
) {

    // record 기본 toString은 모든 필드를 찍으므로, 비밀번호가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "SignUpRequest[loginId=" + loginId + ", password=****, name=" + name + "]";
    }
}
