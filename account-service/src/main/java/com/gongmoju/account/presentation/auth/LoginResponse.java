package com.gongmoju.account.presentation.auth;

import com.gongmoju.account.domain.auth.AccessToken;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인 결과")
public record LoginResponse(
        @Schema(description = "액세스 토큰(JWT). 이후 요청의 Authorization: Bearer <accessToken> 헤더에 사용")
        String accessToken,
        @Schema(description = "토큰 종류", example = "Bearer")
        String tokenType,
        @Schema(description = "만료까지 남은 시간 (초)", example = "3600")
        long expiresIn
) {

    private static final String TOKEN_TYPE = "Bearer";

    public static LoginResponse from(AccessToken token) {
        return new LoginResponse(token.value(), TOKEN_TYPE, token.expiresIn());
    }

    // 토큰은 인증 수단이라 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "LoginResponse[accessToken=****, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
