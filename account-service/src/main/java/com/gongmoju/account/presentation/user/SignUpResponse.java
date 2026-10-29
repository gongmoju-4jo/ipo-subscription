package com.gongmoju.account.presentation.user;

import com.gongmoju.account.application.user.SignUpResult;
import com.gongmoju.account.presentation.support.RequestUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "회원가입 결과 (비밀번호는 포함하지 않음)")
public record SignUpResponse(
        @Schema(description = "회원 ID", example = "7a1c0000-0000-4000-8000-000000000001")
        UUID userId,
        @Schema(description = "로그인 ID", example = "investor01")
        String loginId,
        @Schema(description = "이름", example = "홍길동")
        String name,
        @Schema(description = "가입 시각 (KST)", example = "2026-11-03T10:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static SignUpResponse from(SignUpResult result) {
        return new SignUpResponse(result.userId(), result.loginId(), result.name(),
                RequestUser.toKst(result.createdAt()));
    }
}
