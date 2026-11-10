package com.gongmoju.gateway.queue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// 알림 규칙(QueueAdmissionDown)이 읽는 queue_admission_up이 Prometheus 수집 경로(관리 포트 /actuator/prometheus)에 실제로 나오는지 확인한다.
// 게이트웨이 전체 + Redis 컨테이너. 테스트 설정(application.properties)은 입장 스케줄러를 끄므로 여기서는 다시 켠다.
// 끝나면 컨텍스트를 닫는다: 테스트 컨텍스트는 캐시되어 남아 있는데, 그동안 스케줄러가 같은 Redis 컨테이너를 쓰는 다른 테스트의 대기자를 입장시켜 버린다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "management.server.port=0",
                "management.prometheus.metrics.export.enabled=true", "queue.scheduler.enabled=true"})
@DirtiesContext
class QueueAdmissionMetricTest extends RedisTestSupport {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        redisProperties(registry);
    }

    @LocalManagementPort
    private int managementPort;

    @Test
    void 입장_처리가_돌면_Prometheus_수집_경로에_queue_admission_up_1이_나온다() throws Exception {
        WebTestClient management = WebTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();
        String line = "";
        // 스케줄러가 기동 직후 1초 간격으로 돌므로 몇 초 안에 1이 된다
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            String body = management.get().uri("/actuator/prometheus").exchange()
                    .expectStatus().isOk()
                    .expectBody(String.class).returnResult().getResponseBody();
            line = body.lines().filter(l -> l.startsWith("queue_admission_up")).findFirst().orElse("");
            if (line.endsWith(" 1.0")) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(line).isEqualTo("queue_admission_up 1.0");
    }
}
