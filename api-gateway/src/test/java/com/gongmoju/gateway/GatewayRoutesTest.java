package com.gongmoju.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

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
                        "account-service", "subscription-service", "allocation-service",
                        "account-service-api-docs", "subscription-service-api-docs", "allocation-service-api-docs");
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
