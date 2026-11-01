package com.gongmoju.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너 헬스체크가 쓰는 /actuator/health가 응답하고, health 외 엔드포인트는 노출되지 않는지 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EurekaHealthTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void health는_UP이다() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void health_외_엔드포인트는_노출되지_않는다() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode().value()).isEqualTo(404);
    }
}
