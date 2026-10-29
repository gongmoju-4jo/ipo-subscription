package com.gongmoju.account.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 설정 (application.yml의 jwt.*).
 *
 * @param secret           서명 키. 표준 Base64 문자열이고 디코딩 후 32바이트 이상. api-gateway와 같은 값 (JWT_SECRET)
 * @param issuer           토큰의 iss. api-gateway가 이 값으로 검증한다
 * @param expiresInSeconds 토큰 유효 시간(초)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, String issuer, long expiresInSeconds) {

    // 키가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "JwtProperties[secret=****, issuer=" + issuer + ", expiresInSeconds=" + expiresInSeconds + "]";
    }
}
