package com.gongmoju.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

// 테스트에서 account-service가 발급하는 것과 같은 규격(HS256, sub/role/iss/iat/exp)의 토큰을 만든다
final class JwtTestTokens {

    private static final String ISSUER = "account-service";

    private JwtTestTokens() {
    }

    // 지금부터 1시간 동안 유효한 토큰
    static String valid(String secretBase64, String subject, String role) throws JOSEException {
        Instant now = Instant.now();
        return custom(secretBase64, ISSUER, subject, role, now, now.plus(1, ChronoUnit.HOURS));
    }

    // 이미 만료된 토큰
    static String expired(String secretBase64, String subject, String role) throws JOSEException {
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        return custom(secretBase64, ISSUER, subject, role, issuedAt, issuedAt.plus(1, ChronoUnit.HOURS));
    }

    // 만료 시각(exp)이 없는 토큰
    static String withoutExpiry(String secretBase64, String subject, String role) throws JOSEException {
        return custom(secretBase64, ISSUER, subject, role, Instant.now(), null);
    }

    // 필요한 claim만 골라 담아서 HS256으로 서명한다 (null이면 그 claim은 넣지 않는다)
    static String custom(String secretBase64, String issuer, String subject, String role,
                         Instant issuedAt, Instant expiresAt) throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        if (issuedAt != null) {
            claims.issueTime(Date.from(issuedAt));
        }
        if (expiresAt != null) {
            claims.expirationTime(Date.from(expiresAt));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(Base64.getDecoder().decode(secretBase64)));
        return jwt.serialize();
    }
}
