package com.gongmoju.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 검증 설정 (application.yml의 jwt.*).
 *
 * @param secret 서명 키. 표준 Base64 문자열이고 디코딩 후 32바이트 이상. account-service와 같은 값 (JWT_SECRET)
 * @param issuer 토큰의 iss가 이 값과 같아야 한다 (account-service)
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, String issuer) {

    // 키가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "JwtProperties[secret=****, issuer=" + issuer + "]";
    }
}
