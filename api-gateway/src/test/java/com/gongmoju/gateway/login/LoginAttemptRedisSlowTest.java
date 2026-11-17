package com.gongmoju.gateway.login;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.TcpServer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static com.gongmoju.gateway.login.FakeAccountServiceConfig.BACKEND_HITS;
import static org.assertj.core.api.Assertions.assertThat;

// Redis가 연결은 받는데 답하지 않을 때(멈춤, 지연). Redis 명령 시간 초과(2초)까지 기다리지 않고 login-attempt.redis-timeout 뒤 통과시키고,
// 그 뒤 login-attempt.redis-skip 동안은 Redis를 부르지 않는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "queue.scheduler.enabled=false"})
@Import(FakeAccountServiceConfig.class)
class LoginAttemptRedisSlowTest {

    // 받은 바이트 묶음 수 (게이트웨이가 Redis에 명령을 보냈는지)
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    // 연결만 받고 아무 답도 하지 않는 가짜 Redis
    private static final DisposableServer SILENT_REDIS = TcpServer.create().host("127.0.0.1").port(0)
            .handle((inbound, outbound) -> inbound.receive().doOnNext(bytes -> RECEIVED.incrementAndGet()).then(Mono.never()))
            .bindNow();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", SILENT_REDIS::port);
    }

    @AfterAll
    static void stop() {
        SILENT_REDIS.disposeNow();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void Redis가_답하지_않으면_짧게_기다린_뒤_통과하고_건너뛰는_동안은_Redis를_부르지_않는다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        long first = timeMillis(() -> login("wrong").expectStatus().isUnauthorized());
        // 첫 요청이 보낸 명령(연결 인사 포함)이 도착할 시간을 둔 뒤 센다
        Thread.sleep(300);
        int receivedAfterFirst = RECEIVED.get();

        long second = timeMillis(() -> login("right").expectStatus().isOk());
        Thread.sleep(300);

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore + 2);
        // Redis 명령 시간 초과(2초)가 아니라 redis-timeout(200ms) 근처에서 끝난다 (가짜 서비스 401 지연 100ms 포함)
        assertThat(first).isLessThan(1500);
        assertThat(receivedAfterFirst).isPositive();
        // 건너뛰는 동안은 Redis에 아무것도 보내지 않고 기다리지도 않는다
        assertThat(RECEIVED.get()).isEqualTo(receivedAfterFirst);
        assertThat(second).isLessThan(first);
    }

    private WebTestClient.ResponseSpec login(String password) {
        return webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"loginId\":\"investor01\",\"password\":\"" + password + "\"}")
                .exchange();
    }

    private static long timeMillis(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }
}
