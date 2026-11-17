package com.gongmoju.gateway.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 로그인 시도 제한 테스트용 가짜 계좌 서비스. /api/v1/auth/**를 이쪽으로 보내는 테스트 라우트를 앞에 둔다
 * (application.yml의 실제 라우트는 그대로. 필터는 GlobalFilter라 테스트 라우트에도 똑같이 걸린다).
 *
 * <p>본문의 password로 응답을 정한다: right → 200, wrong → 401 INVALID_CREDENTIALS (동시 시도가 겹치게 100ms 늦게),
 * bad → 400, boom → 500, 그 밖(읽을 수 없는 본문 포함) → 400
 */
@TestConfiguration
public class FakeAccountServiceConfig {

    // 가짜 서비스가 받은 요청 수 (게이트웨이에서 막힌 요청이 서비스까지 가지 않았는지 확인)
    static final AtomicInteger BACKEND_HITS = new AtomicInteger();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Bean(destroyMethod = "disposeNow")
    DisposableServer fakeAccountService() {
        return HttpServer.create().host("localhost").port(0)
                .route(routes -> routes.route(request -> true, (request, response) -> request.receive().aggregate().asString()
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            BACKEND_HITS.incrementAndGet();
                            String password = password(body);
                            int status;
                            String json;
                            Duration delay = Duration.ZERO;
                            switch (password) {
                                case "right" -> {
                                    status = 200;
                                    json = "{\"success\":true,\"data\":{\"accessToken\":\"t\"}}";
                                }
                                case "wrong" -> {
                                    status = 401;
                                    json = "{\"success\":false,\"error\":{\"code\":\"INVALID_CREDENTIALS\"}}";
                                    delay = Duration.ofMillis(100);
                                }
                                case "boom" -> {
                                    status = 500;
                                    json = "{\"success\":false,\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\"}}";
                                }
                                default -> {
                                    status = 400;
                                    json = "{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\"}}";
                                }
                            }
                            return Mono.delay(delay).then(response.status(status)
                                    .header("Content-Type", "application/json")
                                    .sendString(Mono.just(json)).then());
                        })))
                .bindNow();
    }

    @Bean
    RouteLocator fakeAccountRoute(RouteLocatorBuilder builder, DisposableServer fakeAccountService) {
        return builder.routes()
                .route("test-auth", r -> r.order(-1).path("/api/v1/auth/**")
                        .uri("http://localhost:" + fakeAccountService.port()))
                .build();
    }

    private static String password(String body) {
        try {
            JsonNode value = MAPPER.readTree(body).path("password");
            return value.isTextual() ? value.asText() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
