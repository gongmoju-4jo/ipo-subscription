package com.gongmoju.gateway.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * account-service가 발급한 JWT를 검증하는 디코더. 규격(HS256, claim sub/role/iss/iat/exp)은 account-service의 JwtAccessTokenIssuer와 같다.
 * 서명, 만료, iss, sub, role 중 하나라도 맞지 않으면 토큰을 받아들이지 않는다.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class JwtConfig {

    // HS256 서명 키는 32바이트(256비트) 이상이어야 한다
    private static final int MIN_SECRET_BYTES = 32;

    // common UserRole과 같은 값 (Gateway는 common을 쓰지 않는다)
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    @Bean
    ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        if (properties.issuer() == null || properties.issuer().isBlank()) {
            throw new IllegalStateException("jwt.issuer가 비어 있습니다.");
        }
        SecretKeySpec key = new SecretKeySpec(decodeSecret(properties.secret()), "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), issuerValidator(properties.issuer()), claimsValidator()));
        return decoder;
    }

    // 키가 없거나 짧으면 서버를 띄울 때 바로 실패시킨다 (키 값은 메시지에 넣지 않는다)
    static byte[] decodeSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET이 비어 있습니다. .env에 표준 Base64 문자열(디코딩 후 32바이트 이상)을 넣어 주세요.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET이 표준 Base64 문자열이 아닙니다.");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET은 디코딩 후 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
        }
        return decoded;
    }

    // iss는 URL이 아니라 "account-service" 같은 이름이라 getIssuer()를 쓰지 않고 문자열로 비교한다
    private static OAuth2TokenValidator<Jwt> issuerValidator(String issuer) {
        return jwt -> issuer.equals(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(invalid("iss가 일치하지 않습니다."));
    }

    // X-User-Id, X-User-Role로 넘길 값이라 sub가 있고 role이 USER 또는 ADMIN인 토큰만 받는다
    private static OAuth2TokenValidator<Jwt> claimsValidator() {
        return jwt -> {
            String subject = jwt.getSubject();
            if (subject == null || subject.isBlank()) {
                return OAuth2TokenValidatorResult.failure(invalid("sub가 없습니다."));
            }
            String role = jwt.getClaimAsString("role");
            if (role == null || !ROLES.contains(role)) {
                return OAuth2TokenValidatorResult.failure(invalid("role이 올바르지 않습니다."));
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2Error invalid(String description) {
        return new OAuth2Error("invalid_token", description, null);
    }
}
