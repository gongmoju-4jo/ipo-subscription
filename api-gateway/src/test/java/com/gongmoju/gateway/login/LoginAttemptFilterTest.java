package com.gongmoju.gateway.login;

import com.gongmoju.gateway.queue.RedisTestSupport;
import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.gongmoju.gateway.login.FakeAccountServiceConfig.BACKEND_HITS;
import static org.assertj.core.api.Assertions.assertThat;

// 로그인 시도 제한. 가짜 계좌 서비스를 띄우고 실제 Redis(redis:7)로 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "queue.scheduler.enabled=false"})
@Import(FakeAccountServiceConfig.class)
class LoginAttemptFilterTest extends RedisTestSupport {

    private static final String LOGIN_ID = "investor01";

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        redisProperties(registry);
    }

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String secret;

    @BeforeEach
    void setUp() {
        flushAll();
    }

    @Test
    void 네_번_실패해도_다섯_번째_시도는_서비스로_간다() {
        failTimes(LOGIN_ID, 4);
        int hitsBefore = BACKEND_HITS.get();

        login(LOGIN_ID, "wrong").expectStatus().isUnauthorized();

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore + 1);
    }

    @Test
    void 다섯_번_실패하면_서비스로_보내지_않고_429와_남은_초를_준다() {
        failTimes(LOGIN_ID, 5);
        int hitsBefore = BACKEND_HITS.get();

        // 맞는 비밀번호여도 차단 중에는 막는다
        String retryAfter = login(LOGIN_ID, "right")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("TOO_MANY_REQUESTS")
                .jsonPath("$.error.message").isEqualTo("로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.")
                .returnResult().getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
        assertThat(Long.parseLong(retryAfter)).isBetween(890L, 900L);
    }

    @Test
    void 평소_실패_응답에는_남은_횟수가_없다() {
        login(LOGIN_ID, "wrong").expectStatus().isUnauthorized()
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void 성공하면_횟수가_지워져_다시_다섯_번_실패해야_막힌다() {
        failTimes(LOGIN_ID, 3);
        login(LOGIN_ID, "right").expectStatus().isOk();

        failTimes(LOGIN_ID, 4);

        login(LOGIN_ID, "right").expectStatus().isOk();
    }

    @Test
    void 서비스가_400이나_500을_주면_실패로_세지_않는다() {
        for (int i = 0; i < 5; i++) {
            login(LOGIN_ID, "bad").expectStatus().isBadRequest();
            login(LOGIN_ID, "boom").expectStatus().is5xxServerError();
        }

        login(LOGIN_ID, "wrong").expectStatus().isUnauthorized();
    }

    @Test
    void 다른_로그인_ID는_영향이_없다() {
        failTimes(LOGIN_ID, 5);

        login("investor02", "right").expectStatus().isOk();
    }

    @Test
    void 같은_키가_두_번_있는_본문은_400이고_서비스로_보내지_않는다() {
        int hitsBefore = BACKEND_HITS.get();

        post("/api/v1/auth/login", "{\"loginId\":\"decoy\",\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"wrong\"}", null)
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore);
    }

    @Test
    void 로그인_ID가_문자열이_아니면_세지_않고_서비스로_보낸다() {
        for (int i = 0; i < 6; i++) {
            post("/api/v1/auth/login", "{\"loginId\":123,\"password\":\"wrong\"}", null).expectStatus().isUnauthorized();
        }

        assertThat(TEMPLATE.keys("login-fail:*").collectList().block()).isEmpty();
    }

    @Test
    void 본문이_16KB를_넘으면_400() {
        String body = "{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + "x".repeat(16 * 1024) + "\"}";

        post("/api/v1/auth/login", body, null).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.message").isEqualTo("요청 본문이 너무 큽니다.");
    }

    // 토큰이 있으면 공개 경로가 아닌 경로로도 서비스까지 갈 수 있다. 퍼센트 인코딩, 끝 슬래시로 제한을 피하지 못하게 한다
    @Test
    void 퍼센트_인코딩_경로와_끝_슬래시도_막는다() throws Exception {
        failTimes(LOGIN_ID, 5);
        String token = JwtTestTokens.valid(secret, UUID.randomUUID().toString(), "USER");

        post(URI.create("http://localhost:" + port + "/api/v1/auth/%6Cogin"), body(LOGIN_ID, "right"), token)
                .expectStatus().isEqualTo(429);
        post("/api/v1/auth/login/", body(LOGIN_ID, "right"), token).expectStatus().isEqualTo(429);
    }

    @Test
    void 다른_경로와_메서드는_세지_않는다() {
        for (int i = 0; i < 6; i++) {
            webTestClient.get().uri("/api/v1/auth/login").exchange();
        }

        assertThat(TEMPLATE.keys("login-fail:*").collectList().block()).isEmpty();
    }

    @Test
    void 동시에_20번_틀려도_서비스까지_가는_시도는_다섯_번이다() {
        int hitsBefore = BACKEND_HITS.get();
        WebClient client = WebClient.create("http://localhost:" + port);

        List<Integer> statuses = Flux.range(0, 20)
                .flatMap(i -> client.post().uri("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body(LOGIN_ID, "wrong"))
                        .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value())), 20)
                .collectList().block(Duration.ofSeconds(30));

        assertThat(BACKEND_HITS.get() - hitsBefore).isEqualTo(5);
        assertThat(statuses).filteredOn(status -> status == 401).hasSize(5);
        assertThat(statuses).filteredOn(status -> status == 429).hasSize(15);
    }

    @Test
    void 남은_시간은_1초_단위로_올려서_알린다() {
        assertThat(LoginAttemptFilter.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
        assertThat(LoginAttemptFilter.retryAfterSeconds(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(LoginAttemptFilter.retryAfterSeconds(Duration.ofMillis(1000))).isEqualTo(1);
        assertThat(LoginAttemptFilter.retryAfterSeconds(Duration.ofMillis(1001))).isEqualTo(2);
        assertThat(LoginAttemptFilter.retryAfterSeconds(Duration.ofMinutes(15))).isEqualTo(900);
    }

    private void failTimes(String loginId, int times) {
        for (int i = 0; i < times; i++) {
            login(loginId, "wrong").expectStatus().isUnauthorized();
        }
    }

    private WebTestClient.ResponseSpec login(String loginId, String password) {
        return post("/api/v1/auth/login", body(loginId, password), null);
    }

    private WebTestClient.ResponseSpec post(String path, String body, String token) {
        return post(URI.create("http://localhost:" + port + path), body, token);
    }

    private WebTestClient.ResponseSpec post(URI uri, String body, String token) {
        WebTestClient.RequestBodySpec spec = webTestClient.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            spec = spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return spec.bodyValue(body).exchange();
    }

    private static String body(String loginId, String password) {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}";
    }
}
