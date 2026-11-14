package com.gongmoju.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// application.yml의 라우트(spring.cloud.gateway.server.webflux.routes)와 통합 Swagger 설정이 실제로 적용되는지 확인
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class GatewayRoutesTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void 서비스_3개의_API_라우트와_API_문서_라우트가_등록된다() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();

        assertThat(routes)
                .extracting(Route::getId)
                .containsExactlyInAnyOrder(
                        "account-service", "subscription-service", "allocation-service", "allocation-service-admin",
                        "account-service-admin",
                        "account-service-api-docs", "subscription-service-api-docs", "allocation-service-api-docs");
    }

    // 배정 관리자 경로는 배정 서비스로, 관리자 계정 경로는 계좌 서비스로, 나머지 관리자 경로는 그대로 청약 서비스로 간다
    // 두 라우트에 모두 맞는 경로라 라우트 순서가 바뀌면 청약 서비스로 가므로 순서까지 확인한다
    @Test
    void 배정_관리자_경로는_배정_서비스로_관리자_계정_경로는_계좌_서비스로_나머지_관리자_경로는_청약_서비스로_간다() {
        assertThat(routeIdFor("/api/v1/admin/allocations/ipos/8b2d0000-0000-4000-8000-000000000001/replay"))
                .isEqualTo("allocation-service-admin");
        assertThat(routeIdFor("/api/v1/admin/users")).isEqualTo("account-service-admin");
        // 경로 이름이 users로 시작하기만 하는 다른 관리자 경로는 계좌 서비스로 가지 않는다
        assertThat(routeIdFor("/api/v1/admin/usersx")).isEqualTo("subscription-service");
        // 회원가입은 기존 계좌 라우트 그대로
        assertThat(routeIdFor("/api/v1/users")).isEqualTo("account-service");
        assertThat(routeIdFor("/api/v1/admin/ipos")).isEqualTo("subscription-service");
        assertThat(routeIdFor("/api/v1/admin")).isEqualTo("subscription-service");
        // 회원용 배정 API는 기존 라우트 그대로
        assertThat(routeIdFor("/api/v1/allocations/subscriptions/74b6514c-c1eb-3837-968b-8425f63cb692"))
                .isEqualTo("allocation-service");
    }

    // Gateway와 같은 방식으로 고른다: 라우트를 순서대로 보며 조건(Path 등)이 처음 맞는 라우트
    private String routeIdFor(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        return routeLocator.getRoutes()
                .concatMap(route -> Mono.from(route.getPredicate().apply(exchange))
                        .filter(Boolean::booleanValue)
                        .map(matched -> route.getId()))
                .next()
                .block();
    }

    @Test
    void 통합_Swagger_UI가_열리고_서비스_3개_문서를_가리킨다() {
        webTestClient.get().uri("/swagger-ui/index.html")
                .exchange()
                .expectStatus().isOk();

        webTestClient.get().uri("/v3/api-docs/swagger-config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.urls[*].url").value(urls -> assertThat((List<Object>) urls)
                        .containsExactlyInAnyOrder("/account-service/v3/api-docs",
                                "/subscription-service/v3/api-docs",
                                "/allocation-service/v3/api-docs"));
    }
}
