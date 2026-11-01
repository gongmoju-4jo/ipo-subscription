package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 보안 필터 체인을 지난 요청의 헤더를 확인한다. 테스트 안에 받은 헤더를 그대로 돌려주는 경로를 두고 본다.
// 라우트를 거쳐 서비스로 프록시될 때의 헤더는 UserHeaderProxyTest에서 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@Import(UserHeaderInjectionTest.EchoConfig.class)
class UserHeaderInjectionTest {

    private static final String ECHO = "/test-echo/headers";

    @TestConfiguration
    static class EchoConfig {

        @Bean
        RouterFunction<ServerResponse> headerEcho() {
            return RouterFunctions.route().GET(ECHO, request -> {
                Map<String, String> headers = new HashMap<>();
                headers.put("userId", request.headers().firstHeader(GatewayHeaders.USER_ID));
                headers.put("userRole", request.headers().firstHeader(GatewayHeaders.USER_ROLE));
                headers.put("receivedAt", request.headers().firstHeader(GatewayHeaders.REQUEST_RECEIVED_AT));
                return ServerResponse.ok().bodyValue(headers);
            }).build();
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    @Test
    void 검증한_토큰의_sub와_role이_X_User_Id와_X_User_Role로_전달된다() throws Exception {
        webTestClient.get().uri(ECHO).header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "USER")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
    }

    @Test
    void 관리자_토큰은_ADMIN_역할로_전달된다() throws Exception {
        webTestClient.get().uri(ECHO).header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "ADMIN")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("ADMIN");
    }

    @Test
    void 클라이언트가_다른_회원과_ADMIN을_가장해_보내도_토큰의_값으로_덮어쓴다() throws Exception {
        String otherUserId = UUID.randomUUID().toString();

        webTestClient.get().uri(ECHO)
                .header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "USER"))
                .header(GatewayHeaders.USER_ID, otherUserId)
                .header(GatewayHeaders.USER_ROLE, "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
    }

    @Test
    void 도착_시각은_클라이언트_값이_아니라_Gateway가_기록한_값이_전달된다() throws Exception {
        webTestClient.get().uri(ECHO)
                .header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "USER"))
                .header(GatewayHeaders.REQUEST_RECEIVED_AT, "2020-01-01T00:00:00.000Z")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.receivedAt").value(value -> assertThat((String) value)
                        .isNotEqualTo("2020-01-01T00:00:00.000Z")
                        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
    }

    @Test
    void 검증에_실패한_토큰은_401이고_사용자_헤더를_넘기지_않는다() throws Exception {
        webTestClient.get().uri(ECHO)
                .header("Authorization", "Bearer " + JwtTestTokens.expired(secret, userId, "ADMIN"))
                .header(GatewayHeaders.USER_ID, userId)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
