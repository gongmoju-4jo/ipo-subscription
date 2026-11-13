package com.gongmoju.account.presentation.user;

import com.gongmoju.account.application.user.SignUpResult;
import com.gongmoju.account.presentation.support.RequestUser;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "관리자 계정 생성 결과. 회원가입 결과와 같은 항목 (비밀번호는 포함하지 않음)")
public record AdminUserCreateResponse(
        @Schema(description = "회원 ID", example = "7a1c0000-0000-4000-8000-000000000002")
        UUID userId,
        @Schema(description = "로그인 ID", example = "operator01")
        String loginId,
        @Schema(description = "이름", example = "운영자")
        String name,
        @Schema(description = "생성 시각 (KST)", example = "2026-11-14T10:00:00+09:00")
        OffsetDateTime createdAt
) {

    public static AdminUserCreateResponse from(SignUpResult result) {
        return new AdminUserCreateResponse(result.userId(), result.loginId(), result.name(),
                RequestUser.toKst(result.createdAt()));
    }
}
