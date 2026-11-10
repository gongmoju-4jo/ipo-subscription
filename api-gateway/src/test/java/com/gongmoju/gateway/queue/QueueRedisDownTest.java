package com.gongmoju.gateway.queue;

import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

// Redis에 접속할 수 없으면 대기열 없이 통과시키지 않고 503으로 막는다
// (아무것도 듣지 않는 포트 1로 접속하게 해서 연결 실패를 만든다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
class QueueRedisDownTest {

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    @Test
    void Redis에_접속할_수_없으면_진입은_503() throws Exception {
        webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.valid(secret, UUID.randomUUID().toString(), "USER"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
}
