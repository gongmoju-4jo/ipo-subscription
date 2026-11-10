package com.gongmoju.gateway.queue;

import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 대기열 진입의 청약 중 공모주 확인. 게이트웨이 전체 + Redis 컨테이너 + 가짜 청약 서비스(내부 API 4.4)
// 목록 갱신은 테스트가 직접 부른다 (테스트 설정은 스케줄러를 끈다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "queue.open-ipos.load-balanced=false"})
class OpenIpoEntryTest extends RedisTestSupport {

    private static final AtomicReference<String> RESPONSE = new AtomicReference<>();
    private static final AtomicInteger RESPONSE_STATUS = new AtomicInteger(200);
    private static final AtomicReference<String> LAST_URI = new AtomicReference<>();
    private static final AtomicReference<String> LAST_USER_HEADER = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH_HEADER = new AtomicReference<>();

    private static final DisposableServer SUBSCRIPTION = HttpServer.create().host("localhost").port(0)
            .route(routes -> routes.get("/internal/ipos", (request, response) -> {
                LAST_URI.set(request.uri());
                LAST_USER_HEADER.set(request.requestHeaders().get("X-User-Id"));
                LAST_AUTH_HEADER.set(request.requestHeaders().get(HttpHeaders.AUTHORIZATION));
                return response.status(RESPONSE_STATUS.get()).header("Content-Type", "application/json")
                        .sendString(Mono.just(RESPONSE.get()));
            }))
            .bindNow();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        redisProperties(registry);
        registry.add("queue.open-ipos.base-url", () -> "http://localhost:" + SUBSCRIPTION.port());
    }

    @AfterAll
    static void stop() {
        SUBSCRIPTION.disposeNow();
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OpenIpoCatalog catalog;

    @Value("${jwt.secret}")
    private String secret;

    private final UUID open = UUID.randomUUID();
    private final UUID notStarted = UUID.randomUUID();
    private final UUID ended = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        flushAll();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(9)).withNano(0);
        RESPONSE_STATUS.set(200);
        RESPONSE.set(list(
                item(open, now.minusHours(1), now.plusHours(1)),
                item(notStarted, now.plusHours(1), now.plusHours(2)),
                item(ended, now.minusHours(2), now.minusSeconds(1))));
        catalog.refresh().block();
    }

    @Test
    void 청약_중인_공모주는_진입한다() throws Exception {
        enter(open.toString()).expectStatus().isCreated();
        // 대문자로 보내도 같은 공모주
        enter(open.toString().toUpperCase()).expectStatus().isOk();
    }

    @Test
    void 없는_공모주_번호_시작_전_마감된_공모주는_422이고_대기열에_들어가지_않는다() throws Exception {
        for (UUID ipoId : new UUID[]{UUID.randomUUID(), notStarted, ended}) {
            enter(ipoId.toString())
                    .expectStatus().isEqualTo(422)
                    .expectBody()
                    .jsonPath("$.success").isEqualTo(false)
                    .jsonPath("$.error.code").isEqualTo("BUSINESS_RULE_VIOLATION")
                    .jsonPath("$.error.message").isEqualTo("청약 중인 공모주가 아닙니다.")
                    .jsonPath("$.error.details[0].field").isEqualTo("ipoId");

            assertThat(TEMPLATE.opsForZSet().size(QueueRedisRepository.waitingKey(ipoId)).block()).isZero();
        }
    }

    @Test
    void 청약_서비스에_사용자_정보_없이_OPEN_목록만_요청한다() {
        assertThat(LAST_URI.get()).isEqualTo("/internal/ipos?status=OPEN");
        assertThat(LAST_USER_HEADER.get()).isNull();
        assertThat(LAST_AUTH_HEADER.get()).isNull();
    }

    @Test
    void 청약_서비스가_실패하거나_형식이_다르면_마지막_목록을_계속_쓴다() throws Exception {
        RESPONSE_STATUS.set(500);
        RESPONSE.set("{\"success\":false,\"error\":{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"x\"}}");
        catalog.refresh().block();
        enter(UUID.randomUUID().toString()).expectStatus().isEqualTo(422);
        enter(open.toString()).expectStatus().isCreated();

        // 필수 값이 빠진 응답도 받지 않은 것으로 본다 (일부만 쓰면 빠진 공모주의 진입이 막힘)
        RESPONSE_STATUS.set(200);
        RESPONSE.set("{\"success\":true,\"data\":[{\"ipoId\":\"" + UUID.randomUUID() + "\"}]}");
        catalog.refresh().block();
        enter(open.toString()).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec enter(String ipoId) throws Exception {
        return webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", ipoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.valid(secret, "7a1c0000-0000-4000-8000-000000000001", "USER"))
                .exchange();
    }

    private static String list(String... items) {
        return "{\"success\":true,\"data\":[" + String.join(",", items) + "],\"message\":\"청약 중인 공모주 목록을 조회했습니다.\"}";
    }

    private static String item(UUID ipoId, OffsetDateTime start, OffsetDateTime end) {
        return "{\"ipoId\":\"" + ipoId + "\",\"subscriptionStartAt\":\"" + start + "\",\"subscriptionEndAt\":\"" + end + "\"}";
    }
}
