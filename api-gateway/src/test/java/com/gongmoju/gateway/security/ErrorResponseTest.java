package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

// 인증, 권한 실패가 서비스와 같은 공통 오류 형식(API 명세 1.5, 1.6)으로 내려가는지 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@Import(UserHeaderInjectionTest.EchoConfig.class)
class ErrorResponseTest {

    private static final String PROTECTED = "/api/v1/accounts";
    private static final String UNAUTHORIZED_MESSAGE = "인증이 필요합니다.";
    // 서명 위조 확인용 다른 키 (실제 키 아님). 디코딩 후 32바이트 이상
    private static final String OTHER_SECRET = Base64.getEncoder()
            .encodeToString("another-test-only-key-0123456789abcdef".getBytes());

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    @Test
    void 토큰이_없으면_401_UNAUTHORIZED를_공통_형식으로_응답한다() {
        get(PROTECTED, null)
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                .expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.error.message").isEqualTo(UNAUTHORIZED_MESSAGE)
                .jsonPath("$.error.details").doesNotExist()
                .jsonPath("$.data").doesNotExist();
    }

    @Test
    void 만료된_토큰은_401_TOKEN_EXPIRED다() throws Exception {
        get(PROTECTED, JwtTestTokens.expired(secret, userId, "USER"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("TOKEN_EXPIRED")
                .jsonPath("$.error.message").isEqualTo("토큰이 만료되었습니다.");
    }

    @Test
    void 서명이_위조되었거나_형식이_깨진_토큰은_만료가_아니라_UNAUTHORIZED다() throws Exception {
        for (String token : new String[]{JwtTestTokens.valid(OTHER_SECRET, userId, "USER"), "not-a-jwt", "aaa.bbb.ccc"}) {
            get(PROTECTED, token)
                    .expectStatus().isUnauthorized()
                    .expectBody()
                    .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED")
                    .jsonPath("$.error.message").isEqualTo(UNAUTHORIZED_MESSAGE);
        }
    }

    @Test
    void 만료된_토큰이_위조되었다면_만료가_아니라_UNAUTHORIZED다() throws Exception {
        // 서명이 틀린 토큰은 만료 여부를 알려주지 않는다
        get(PROTECTED, JwtTestTokens.expired(OTHER_SECRET, userId, "USER"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void exp가_없는_토큰은_영원히_유효해지므로_401이다() throws Exception {
        get(PROTECTED, JwtTestTokens.withoutExpiry(secret, userId, "USER"))
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 일반_회원이_관리자_경로를_호출하면_403_FORBIDDEN을_공통_형식으로_응답한다() throws Exception {
        get("/api/v1/admin/ipos", JwtTestTokens.valid(secret, userId, "USER"))
                .expectStatus().isForbidden()
                .expectHeader().contentType(new MediaType("application", "json", StandardCharsets.UTF_8))
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("FORBIDDEN")
                .jsonPath("$.error.message").isEqualTo("관리자 권한이 필요합니다.")
                .jsonPath("$.error.details").doesNotExist();
    }

    @Test
    void 관리자_경로에_토큰이_없으면_403이_아니라_401_UNAUTHORIZED다() {
        webTestClient.method(HttpMethod.POST).uri("/api/v1/admin/ipos").exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 인증에_성공한_요청의_응답은_바뀌지_않는다() throws Exception {
        get("/test-echo/headers", JwtTestTokens.valid(secret, userId, "USER"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.error").doesNotExist();
    }

    private WebTestClient.ResponseSpec get(String path, String token) {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.get().uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange();
    }
}
