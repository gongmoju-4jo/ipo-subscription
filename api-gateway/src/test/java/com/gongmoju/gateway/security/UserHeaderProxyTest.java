package com.gongmoju.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// Gateway가 라우트로 서비스에 프록시할 때 서비스가 받는 헤더를 확인한다.
// 테스트 안에서 가짜 서비스를 랜덤 포트로 띄우고 그쪽으로 가는 테스트용 라우트를 추가한다 (application.yml의 실제 라우트는 그대로)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@Import(UserHeaderProxyTest.FakeServiceConfig.class)
class UserHeaderProxyTest {

    private static final String PATH = "/test-backend/headers";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 가짜 서비스가 받은 요청 수 (Gateway에서 막힌 요청이 서비스까지 가지 않았는지 확인)
    private static final AtomicInteger BACKEND_HITS = new AtomicInteger();

    @TestConfiguration
    static class FakeServiceConfig {

        // 받은 헤더를 그대로 JSON으로 돌려주는 가짜 서비스
        @Bean(destroyMethod = "disposeNow")
        DisposableServer fakeService() {
            return HttpServer.create().host("localhost").port(0)
                    .route(routes -> routes.get(PATH, (request, response) -> {
                        BACKEND_HITS.incrementAndGet();
                        Map<String, String> headers = new HashMap<>();
                        headers.put("userId", request.requestHeaders().get(GatewayHeaders.USER_ID));
                        headers.put("userRole", request.requestHeaders().get(GatewayHeaders.USER_ROLE));
                        headers.put("receivedAt", request.requestHeaders().get(GatewayHeaders.REQUEST_RECEIVED_AT));
                        return response.header("Content-Type", "application/json").sendString(Mono.just(json(headers)));
                    }))
                    .bindNow();
        }

        @Bean
        RouteLocator fakeServiceRoute(RouteLocatorBuilder builder, DisposableServer fakeService) {
            return builder.routes()
                    .route("test-fake-service", r -> r.path("/test-backend/**").uri("http://localhost:" + fakeService.port()))
                    .build();
        }

        private static String json(Map<String, String> value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    @Test
    void 서비스는_검증한_토큰의_sub와_role을_헤더로_받는다() throws Exception {
        webTestClient.get().uri(PATH).header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "USER")).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
    }

    @Test
    void 클라이언트가_다른_회원과_ADMIN을_가장해_보내도_서비스는_토큰의_값을_받는다() throws Exception {
        webTestClient.get().uri(PATH)
                .header("Authorization", "Bearer " + JwtTestTokens.valid(secret, userId, "USER"))
                .header(GatewayHeaders.USER_ID, UUID.randomUUID().toString())
                .header(GatewayHeaders.USER_ROLE, "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.userId").isEqualTo(userId)
                .jsonPath("$.userRole").isEqualTo("USER");
    }

    @Test
    void 서비스는_클라이언트가_보낸_도착_시각이_아니라_Gateway가_기록한_시각을_받는다() throws Exception {
        webTestClient.get().uri(PATH)
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
    void 검증에_실패한_요청은_401이고_서비스까지_가지_않는다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        webTestClient.get().uri(PATH)
                .header("Authorization", "Bearer " + JwtTestTokens.expired(secret, userId, "ADMIN"))
                .header(GatewayHeaders.USER_ID, userId)
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }
}
