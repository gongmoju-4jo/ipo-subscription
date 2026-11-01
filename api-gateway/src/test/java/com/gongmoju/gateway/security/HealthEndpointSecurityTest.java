package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.web.reactive.server.WebTestClient;

// 컨테이너 헬스체크용으로 관리 포트의 health만 토큰 없이 열려 있고, 서비스 포트(8080)와 다른 엔드포인트는 계속 막혀 있는지 확인한다.
// 관리 포트는 테스트에서 임의 포트(0)로 띄운다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "management.server.port=0"})
class HealthEndpointSecurityTest {

    @Autowired
    private WebTestClient webTestClient;

    @LocalManagementPort
    private int managementPort;

    private WebTestClient management() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
    }

    @Test
    void 관리_포트의_health는_토큰_없이_UP이다() {
        management().get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void 서비스_포트의_actuator는_토큰_없이_열리지_않는다() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void 관리_포트에서도_health_외_엔드포인트는_열리지_않는다() {
        management().get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();
        management().get().uri("/actuator").exchange().expectStatus().isUnauthorized();
        management().get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void 관리_포트로_API_경로를_요청해도_인증이_필요하다() {
        management().get().uri("/api/v1/accounts").exchange().expectStatus().isUnauthorized();
    }
}
