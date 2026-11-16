package com.gongmoju.account.presentation.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import static com.gongmoju.account.domain.user.UserInputRules.LOGIN_ID_REGEX;
import static com.gongmoju.account.domain.user.UserInputRules.LOGIN_ID_RULE;
import static com.gongmoju.account.domain.user.UserInputRules.NAME_MAX_LENGTH;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_MAX_LENGTH;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_MIN_LENGTH;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_RULE;

@Schema(description = "회원가입 요청. 역할(role)은 받지 않고 가입자는 항상 USER")
public record SignUpRequest(
        @Schema(description = "로그인 ID (" + LOGIN_ID_RULE + ")", example = "investor01")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = LOGIN_ID_REGEX, message = LOGIN_ID_RULE + "만 사용할 수 있습니다.")
        String loginId,
        @Schema(description = "비밀번호 (" + PASSWORD_RULE + ")", example = "password1234")
        @NotBlank(message = "필수값입니다.")
        @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH, message = PASSWORD_RULE + "여야 합니다.")
        String password,
        @Schema(description = "이름 (1~" + NAME_MAX_LENGTH + "자)", example = "홍길동")
        @NotBlank(message = "필수값입니다.")
        @Size(max = NAME_MAX_LENGTH, message = NAME_MAX_LENGTH + "자 이하여야 합니다.")
        String name
) {

    // record 기본 toString은 모든 필드를 찍으므로, 비밀번호가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "SignUpRequest[loginId=" + loginId + ", password=****, name=" + name + "]";
    }
}
