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
        return sign(secretBase64, subject, role, now, now.plus(1, ChronoUnit.HOURS));
    }

    // 이미 만료된 토큰
    static String expired(String secretBase64, String subject, String role) throws JOSEException {
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        return sign(secretBase64, subject, role, issuedAt, issuedAt.plus(1, ChronoUnit.HOURS));
    }

    // 만료 시각(exp)이 없는 토큰
    static String withoutExpiry(String secretBase64, String subject, String role) throws JOSEException {
        return serialize(secretBase64, new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).claim("role", role).issueTime(new Date()).build());
    }

    private static String sign(String secretBase64, String subject, String role, Instant issuedAt, Instant expiresAt)
            throws JOSEException {
        return serialize(secretBase64, new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).claim("role", role)
                .issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiresAt)).build());
    }

    private static String serialize(String secretBase64, JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(secretBase64)));
        return jwt.serialize();
    }
}
