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
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
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

    // 서버끼리 시계가 조금 달라도 만료 직후의 토큰을 바로 거절하지 않는다 (Spring 기본값과 같음)
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    // 만료 실패를 다른 실패와 구분하는 오류 코드. 인증 실패 응답이 TOKEN_EXPIRED를 고를 때 쓴다
    static final String EXPIRED_ERROR_CODE = "token_expired";

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
                timestampValidator(), issuerValidator(properties.issuer()), claimsValidator()));
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

    // 만료는 전용 오류 코드로 알린다. exp가 없는 토큰은 영원히 유효해지므로 받지 않는다
    private static OAuth2TokenValidator<Jwt> timestampValidator() {
        return jwt -> {
            Instant now = Instant.now();
            Instant expiresAt = jwt.getExpiresAt();
            if (expiresAt == null) {
                return OAuth2TokenValidatorResult.failure(invalid("exp가 없습니다."));
            }
            if (now.minus(CLOCK_SKEW).isAfter(expiresAt)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED_ERROR_CODE, "토큰이 만료되었습니다.", null));
            }
            Instant notBefore = jwt.getNotBefore();
            if (notBefore != null && now.plus(CLOCK_SKEW).isBefore(notBefore)) {
                return OAuth2TokenValidatorResult.failure(invalid("아직 유효하지 않은 토큰입니다."));
            }
            return OAuth2TokenValidatorResult.success();
        };
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
