package com.gongmoju.account.infrastructure.security;

import com.gongmoju.account.domain.auth.AccessToken;
import com.gongmoju.account.domain.auth.AccessTokenIssuer;
import com.gongmoju.common.auth.UserRole;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * HS256 JWT 발급. claim은 sub(회원 ID), role, iss, iat, exp다.
 * 이 규격을 api-gateway가 그대로 검증하므로 바꾸면 Gateway도 같이 바꾼다.
 */
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    // HS256 서명 키는 32바이트(256비트) 이상이어야 한다
    private static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final String issuer;
    private final long expiresInSeconds;
    private final Clock clock;

    public JwtAccessTokenIssuer(JwtProperties properties, Clock clock) {
        byte[] secret = decodeSecret(properties.secret());
        if (properties.issuer() == null || properties.issuer().isBlank()) {
            throw new IllegalStateException("jwt.issuer가 비어 있습니다.");
        }
        if (properties.expiresInSeconds() <= 0) {
            throw new IllegalStateException("jwt.expires-in-seconds는 0보다 커야 합니다.");
        }
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(secret, "HmacSHA256")));
        this.issuer = properties.issuer();
        this.expiresInSeconds = properties.expiresInSeconds();
        this.clock = clock;
    }

    @Override
    public AccessToken issue(UUID userId, UserRole role) {
        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("role", role.name())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plusSeconds(expiresInSeconds))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new AccessToken(token, expiresInSeconds);
    }

    // 키가 없거나 짧으면 서버를 띄울 때 바로 실패시킨다 (키 값은 메시지에 넣지 않는다)
    private static byte[] decodeSecret(String secret) {
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
}
