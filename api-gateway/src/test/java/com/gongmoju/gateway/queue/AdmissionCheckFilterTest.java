package com.gongmoju.gateway.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.gateway.security.GatewayHeaders;
import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 청약 신청 전 입장 허가 확인. 가짜 청약 서비스를 띄우고 /api/v1/subscriptions를 그쪽으로 보내는 테스트용 라우트를 앞에 둔다
// (application.yml의 실제 라우트는 그대로. 필터는 GlobalFilter라 테스트 라우트에도 똑같이 걸린다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "queue.scheduler.enabled=false"})
@Import(AdmissionCheckFilterTest.FakeSubscriptionServiceConfig.class)
class AdmissionCheckFilterTest extends RedisTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 가짜 서비스가 받은 요청 수 (게이트웨이에서 막힌 요청이 서비스까지 가지 않았는지 확인)
    private static final AtomicInteger BACKEND_HITS = new AtomicInteger();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        redisProperties(registry);
    }

    @TestConfiguration
    static class FakeSubscriptionServiceConfig {

        // 받은 본문과 헤더를 JSON으로 돌려주는 가짜 청약 서비스
        @Bean(destroyMethod = "disposeNow")
        DisposableServer fakeSubscriptionService() {
            return HttpServer.create().host("localhost").port(0)
                    .route(routes -> routes.route(request -> true, (request, response) -> request.receive().aggregate().asString()
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                BACKEND_HITS.incrementAndGet();
                                Map<String, String> echo = new LinkedHashMap<>();
                                echo.put("body", body);
                                echo.put("receivedAt", request.requestHeaders().get(GatewayHeaders.REQUEST_RECEIVED_AT));
                                echo.put("userId", request.requestHeaders().get(GatewayHeaders.USER_ID));
                                return response.header("Content-Type", "application/json").sendString(Mono.just(json(echo))).then();
                            })))
                    .bindNow();
        }

        @Bean
        RouteLocator fakeSubscriptionRoute(RouteLocatorBuilder builder, DisposableServer fakeSubscriptionService) {
            return builder.routes()
                    .route("test-subscription", r -> r.order(-1).path("/api/v1/subscriptions/**")
                            .uri("http://localhost:" + fakeSubscriptionService.port()))
                    .build();
        }

        private static String json(Map<String, String> value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String secret;

    private final QueueRedisRepository repository = new QueueRedisRepository(TEMPLATE);
    private final UUID ipoId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        flushAll();
    }

    @Test
    void 입장_허가가_있으면_서비스가_같은_본문과_게이트웨이_도착_시각을_받는다() throws Exception {
        admit(ipoId, userId);
        String body = body(ipoId.toString());
        int hitsBefore = BACKEND_HITS.get();
        Instant before = Instant.now();

        Map<?, ?> echo = subscribe("/api/v1/subscriptions", body, token(userId))
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore + 1);
        assertThat(echo.get("body")).isEqualTo(body);
        assertThat(echo.get("userId")).isEqualTo(userId.toString());
        Instant receivedAt = Instant.parse((String) echo.get("receivedAt"));
        assertThat(receivedAt).isBetween(before.minusMillis(1), Instant.now());
    }

    @Test
    void 입장_허가가_없으면_403이고_서비스까지_가지_않는다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        subscribe("/api/v1/subscriptions", body(ipoId.toString()), token(userId))
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("QUEUE_ADMISSION_REQUIRED")
                .jsonPath("$.error.message").isEqualTo("대기열 입장 허가가 필요합니다. 대기열에 진입해주세요.");

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 대기_중이어도_허가_전이면_403() throws Exception {
        repository.enter(ipoId, userId, Instant.now(), Instant.now().plusSeconds(60)).block();

        subscribe("/api/v1/subscriptions", body(ipoId.toString()), token(userId)).expectStatus().isForbidden();
    }

    @Test
    void 다른_공모주의_허가로는_403() throws Exception {
        admit(UUID.randomUUID(), userId);

        subscribe("/api/v1/subscriptions", body(ipoId.toString()), token(userId)).expectStatus().isForbidden();
    }

    @Test
    void 다른_사람의_허가로는_403() throws Exception {
        admit(ipoId, UUID.randomUUID());

        subscribe("/api/v1/subscriptions", body(ipoId.toString()), token(userId)).expectStatus().isForbidden();
    }

    @Test
    void 대문자_ipoId도_같은_공모주의_허가로_통과한다() throws Exception {
        admit(ipoId, userId);

        subscribe("/api/v1/subscriptions", body(ipoId.toString().toUpperCase()), token(userId)).expectStatus().isOk();
    }

    @Test
    void 이탈하면_허가가_없어져_403() throws Exception {
        admit(ipoId, userId);
        repository.leave(ipoId, userId).block();

        subscribe("/api/v1/subscriptions", body(ipoId.toString()), token(userId)).expectStatus().isForbidden();
    }

    @Test
    void 같은_키가_두_번_있으면_허가가_있어도_400이고_서비스까지_가지_않는다() throws Exception {
        admit(ipoId, userId);
        UUID other = UUID.randomUUID();
        int hitsBefore = BACKEND_HITS.get();

        // 허가 있는 공모주가 앞이든 뒤든, 다른 키가 중복이어도 막는다
        for (String body : new String[]{
                "{\"ipoId\":\"" + ipoId + "\",\"ipoId\":\"" + other + "\",\"requestedQuantity\":10}",
                "{\"ipoId\":\"" + other + "\",\"ipoId\":\"" + ipoId + "\",\"requestedQuantity\":10}",
                "{\"ipoId\":\"" + ipoId + "\",\"requestedQuantity\":10,\"requestedQuantity\":20}"}) {
            subscribe("/api/v1/subscriptions", body, token(userId))
                    .expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
        }

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 키_이름을_유니코드_이스케이프로_써도_ipoId로_읽어_허가를_확인한다() throws Exception {
        String body = "{\"ipo\\u0049d\":\"" + ipoId + "\",\"requestedQuantity\":10}";

        subscribe("/api/v1/subscriptions", body, token(userId)).expectStatus().isForbidden();
    }

    // 서비스(Spring MVC)는 UTF 계열이 아닌 문자셋이면 그 문자셋으로 풀어 읽는다. 게이트웨이도 같게 읽어야 허가 없이 빠져나가지 못한다
    @Test
    void 다른_문자셋으로_인코딩한_본문도_서비스와_같게_읽어_허가를_확인한다() throws Exception {
        admit(ipoId, userId);
        for (String charset : new String[]{"IBM037", "UTF-16", "UTF-16LE", "UTF-32BE", "ISO-8859-1"}) {
            Charset cs = Charset.forName(charset);
            MediaType type = new MediaType(MediaType.APPLICATION_JSON, cs);
            int hitsBefore = BACKEND_HITS.get();

            // 허가 없는 공모주 → 403
            webTestClient.post().uri("/api/v1/subscriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                    .contentType(type)
                    .bodyValue(body(UUID.randomUUID().toString()).getBytes(cs))
                    .exchange()
                    .expectStatus().isForbidden();
            assertThat(BACKEND_HITS.get()).as(charset).isEqualTo(hitsBefore);

            // 허가 있는 공모주 → 본문 바이트 그대로 서비스로
            byte[] bytes = body(ipoId.toString()).getBytes(cs);
            webTestClient.post().uri("/api/v1/subscriptions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                    .contentType(type)
                    .bodyValue(bytes)
                    .exchange()
                    .expectStatus().isOk();
            assertThat(BACKEND_HITS.get()).as(charset).isEqualTo(hitsBefore + 1);
        }
    }

    @Test
    void 문자셋_선언_없이_UTF_16_본문을_보내도_자동_감지로_읽어_허가를_확인한다() throws Exception {
        webTestClient.post().uri("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(ipoId.toString()).getBytes(StandardCharsets.UTF_16BE))
                .exchange()
                .expectStatus().isForbidden();
    }

    // 지금은 Spring Security 방화벽(StrictServerWebExchangeFirewall)이 표준이 아닌 메서드를 먼저 400으로 막는다.
    // 필터도 대소문자를 가리지 않고 비교하므로 방화벽 설정이 바뀌어도 허가 확인 없이 빠져나가지 않는다
    @Test
    void 메서드를_소문자로_보내도_서비스까지_가지_않는다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        webTestClient.method(HttpMethod.valueOf("post")).uri("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(ipoId.toString()))
                .exchange()
                .expectStatus().value(status -> assertThat(status).isIn(400, 403));

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 경로를_점_세미콜론_중복_슬래시로_바꿔도_서비스까지_가지_않는다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        for (String path : new String[]{"/api/v1/subscriptions;x=1", "/api/v1/subscriptions/.", "//api/v1/subscriptions",
                "/api/v1/./subscriptions", "/api/v1/x/../subscriptions", "/api/v1/subscriptions%2F", "/api/v1/%2e/subscriptions"}) {
            webTestClient.post().uri(URI.create("http://localhost:" + port + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body(ipoId.toString()))
                    .exchange()
                    .expectStatus().value(status -> assertThat(status).as(path).isIn(400, 403, 404));
        }

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void Content_Type을_해석할_수_없으면_확인_없이_서비스로_보낸다() throws Exception {
        // 서비스도 같은 Spring 파서라 415로 거절한다. WebTestClient는 보내기 전에 Content-Type을 해석하므로 원본 그대로 보낸다
        String token = token(userId);
        Integer status = HttpClient.create()
                .headers(headers -> headers
                        .set(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .set(HttpHeaders.CONTENT_TYPE, "application/json; charset=no-such-charset"))
                .post().uri("http://localhost:" + port + "/api/v1/subscriptions")
                .send(ByteBufFlux.fromString(Mono.just(body(ipoId.toString()))))
                .responseSingle((response, content) -> Mono.just(response.status().code()))
                .block(Duration.ofSeconds(10));

        assertThat(status).isEqualTo(200);
    }

    @Test
    void 본문을_읽을_수_없거나_ipoId가_UUID가_아니면_확인_없이_서비스로_보낸다() throws Exception {
        for (String body : new String[]{"not json", "{\"ipoId\":\"not-a-uuid\"}", "{\"ipoId\":123}", "{}", "[]"}) {
            int hitsBefore = BACKEND_HITS.get();

            subscribe("/api/v1/subscriptions", body, token(userId))
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.body").isEqualTo(body);

            assertThat(BACKEND_HITS.get()).as(body).isEqualTo(hitsBefore + 1);
        }
    }

    @Test
    void 본문이_없어도_서비스로_보낸다() throws Exception {
        webTestClient.post().uri("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.body").isEqualTo("");
    }

    @Test
    void 경로를_인코딩하거나_끝_슬래시를_붙여도_허가를_확인한다() throws Exception {
        int hitsBefore = BACKEND_HITS.get();

        for (String path : new String[]{"/api/v1/%73ubscriptions", "/api/v1/subscriptions/"}) {
            webTestClient.post().uri(URI.create("http://localhost:" + port + path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body(ipoId.toString()))
                    .exchange()
                    .expectStatus().isForbidden();
        }

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 청약_신청이_아닌_요청은_확인하지_않는다() throws Exception {
        webTestClient.get().uri("/api/v1/subscriptions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .exchange()
                .expectStatus().isOk();
        webTestClient.get().uri("/api/v1/subscriptions/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void 본문이_상한보다_크면_400이고_서비스까지_가지_않는다() throws Exception {
        admit(ipoId, userId);
        String body = "{\"ipoId\":\"" + ipoId + "\",\"pad\":\"" + "x".repeat(AdmissionCheckFilter.MAX_BODY_BYTES) + "\"}";
        int hitsBefore = BACKEND_HITS.get();

        subscribe("/api/v1/subscriptions", body, token(userId))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 토큰이_없으면_허가_확인_전에_401() {
        webTestClient.post().uri("/api/v1/subscriptions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(ipoId.toString()))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private void admit(UUID ipo, UUID user) {
        repository.enter(ipo, user, Instant.now(), Instant.now().plusSeconds(60)).block();
        repository.admit(ipo, Instant.now(), 1, Duration.ofMinutes(5), 1000).block();
        assertThat(repository.isAdmitted(ipo, user).block()).isTrue();
    }

    private WebTestClient.ResponseSpec subscribe(String path, String body, String token) {
        return webTestClient.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String body(String ipo) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\",\"ipoId\":\"" + ipo + "\",\"accountId\":\"" + UUID.randomUUID()
                + "\",\"requestedQuantity\":10}";
    }

    private String token(UUID subject) throws Exception {
        return JwtTestTokens.valid(secret, subject.toString(), "USER");
    }
}
