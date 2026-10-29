package com.gongmoju.gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

// account-service가 발급한 토큰과 같은 규격(HS256, sub/role/iss/iat/exp)을 Gateway가 검증하는지 확인한다.
// 서비스 없이 확인하려고 Gateway가 직접 응답하는 Swagger 설정 경로를 호출한다. 토큰이 없는 요청의 차단은 이후 커밋에서 다룬다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class JwtAuthenticationTest {

    private static final String PATH = "/v3/api-docs/swagger-config";
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
        call(token(secret, ISSUER, userId, "USER", valid())).expectStatus().isOk();
    }

    @Test
    void 유효한_ADMIN_토큰도_통과한다() throws Exception {
        call(token(secret, ISSUER, userId, "ADMIN", valid())).expectStatus().isOk();
    }

    @Test
    void 다른_키로_서명한_토큰은_401이다() throws Exception {
        call(token(OTHER_SECRET, ISSUER, userId, "USER", valid())).expectStatus().isUnauthorized();
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
        Instant issuedAt = Instant.now().minus(2, ChronoUnit.HOURS);
        call(token(secret, ISSUER, userId, "USER", new Instant[]{issuedAt, issuedAt.plus(1, ChronoUnit.HOURS)}))
                .expectStatus().isUnauthorized();
    }

    @Test
    void iss가_다르거나_없는_토큰은_401이다() throws Exception {
        call(token(secret, "other-service", userId, "USER", valid())).expectStatus().isUnauthorized();
        call(token(secret, null, userId, "USER", valid())).expectStatus().isUnauthorized();
    }

    @Test
    void role이_없거나_USER_ADMIN이_아닌_토큰은_401이다() throws Exception {
        call(token(secret, ISSUER, userId, null, valid())).expectStatus().isUnauthorized();
        call(token(secret, ISSUER, userId, "SUPERUSER", valid())).expectStatus().isUnauthorized();
        call(token(secret, ISSUER, userId, "user", valid())).expectStatus().isUnauthorized();
    }

    @Test
    void sub가_없는_토큰은_401이다() throws Exception {
        call(token(secret, ISSUER, null, "USER", valid())).expectStatus().isUnauthorized();
    }

    @Test
    void 형식이_깨진_토큰은_401이다() {
        call("not-a-jwt").expectStatus().isUnauthorized();
        call("aaa.bbb.ccc").expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec call(String token) {
        return webTestClient.get().uri(PATH).header("Authorization", "Bearer " + token).exchange();
    }

    // 지금부터 1시간 동안 유효한 [iat, exp]
    private static Instant[] valid() {
        Instant now = Instant.now();
        return new Instant[]{now, now.plus(1, ChronoUnit.HOURS)};
    }

    // 필요한 claim만 골라 담아서 HS256으로 서명한다 (null이면 그 claim은 넣지 않는다)
    private static String token(String secretBase64, String issuer, String subject, String role, Instant[] times)
            throws JOSEException {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issueTime(Date.from(times[0]))
                .expirationTime(Date.from(times[1]));
        if (issuer != null) {
            claims.issuer(issuer);
        }
        if (subject != null) {
            claims.subject(subject);
        }
        if (role != null) {
            claims.claim("role", role);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(Base64.getDecoder().decode(secretBase64)));
        return jwt.serialize();
    }
}
