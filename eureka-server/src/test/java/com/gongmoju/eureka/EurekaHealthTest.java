package com.gongmoju.eureka;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너 헬스체크가 쓰는 /actuator/health와 Prometheus 수집용 /actuator/prometheus가 응답하고, 그 외 엔드포인트는 노출되지 않는지 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.prometheus.metrics.export.enabled=true")
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
    void prometheus는_지표를_준다() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    void health_prometheus_외_엔드포인트는_노출되지_않는다() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(restTemplate.getForEntity("/actuator/beans", String.class).getStatusCode().value()).isEqualTo(404);
    }
}
