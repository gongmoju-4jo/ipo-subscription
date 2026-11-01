package com.gongmoju.gateway.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

// account-service가 발급한 토큰과 같은 규격(HS256, sub/role/iss/iat/exp)을 Gateway가 검증하는지 확인한다.
// 서비스 없이 확인하려고 받은 헤더를 돌려주는 테스트용 경로를 호출한다. 공개 경로는 토큰을 읽지 않으므로 보호되는 경로를 쓴다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@Import(UserHeaderInjectionTest.EchoConfig.class)
class JwtAuthenticationTest {

    private static final String PATH = "/test-echo/headers";
    private static final String ISSUER = "account-service";
    // 서명 위조 확인용 다른 키 (실제 키 아님). 디코딩 후 32바이트 이상
    private static final String OTHER_SECRET = Base64.getEncoder()
            .encodeToString("another-test-only-key-0123456789abcdef".getBytes());

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    @Test
    void 유효한_USER_토큰은_통과한다() throws Exception {
        call(JwtTestTokens.valid(secret, userId, "USER")).expectStatus().isOk();
    }

    @Test
    void 유효한_ADMIN_토큰도_통과한다() throws Exception {
        call(JwtTestTokens.valid(secret, userId, "ADMIN")).expectStatus().isOk();
    }

    @Test
    void 다른_키로_서명한_토큰은_401이다() throws Exception {
        call(JwtTestTokens.valid(OTHER_SECRET, userId, "USER")).expectStatus().isUnauthorized();
    }

    @Test
    void 서명이_없는_토큰은_401이다() {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(userId).claim("role", "ADMIN")
                .expirationTime(Date.from(Instant.now().plus(1, ChronoUnit.HOURS))).build()).serialize();

        call(unsigned).expectStatus().isUnauthorized();
    }

    @Test
    void 만료된_토큰은_401이다() throws Exception {
        call(JwtTestTokens.expired(secret, userId, "USER")).expectStatus().isUnauthorized();
    }

    @Test
    void 만료_시각이_없는_토큰은_401이다() throws Exception {
        call(JwtTestTokens.withoutExpiry(secret, userId, "USER")).expectStatus().isUnauthorized();
    }

    @Test
    void iss가_다르거나_없는_토큰은_401이다() throws Exception {
        call(withClaims("other-service", userId, "USER")).expectStatus().isUnauthorized();
        call(withClaims(null, userId, "USER")).expectStatus().isUnauthorized();
    }

    @Test
    void role이_없거나_USER_ADMIN이_아닌_토큰은_401이다() throws Exception {
        call(withClaims(ISSUER, userId, null)).expectStatus().isUnauthorized();
        call(withClaims(ISSUER, userId, "SUPERUSER")).expectStatus().isUnauthorized();
        call(withClaims(ISSUER, userId, "user")).expectStatus().isUnauthorized();
    }

    @Test
    void sub가_없는_토큰은_401이다() throws Exception {
        call(withClaims(ISSUER, null, "USER")).expectStatus().isUnauthorized();
    }

    @Test
    void 형식이_깨진_토큰은_401이다() {
        call("not-a-jwt").expectStatus().isUnauthorized();
        call("aaa.bbb.ccc").expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec call(String token) {
        return webTestClient.get().uri(PATH).header("Authorization", "Bearer " + token).exchange();
    }

    // 지금부터 1시간 동안 유효하고 claim만 골라 담은 토큰 (null이면 그 claim은 넣지 않는다)
    private String withClaims(String issuer, String subject, String role) throws Exception {
        Instant now = Instant.now();
        return JwtTestTokens.custom(secret, issuer, subject, role, now, now.plus(1, ChronoUnit.HOURS));
    }
}
