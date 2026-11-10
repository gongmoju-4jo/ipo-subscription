package com.gongmoju.gateway.queue;

import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 대기열 API를 게이트웨이 전체(보안 포함) + Redis 컨테이너로 확인한다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class QueueControllerTest extends RedisTestSupport {

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        redisProperties(registry);
    }

    @Autowired
    private WebTestClient webTestClient;

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
    void 처음_진입하면_201과_대기_상태를_돌려준다() throws Exception {
        enter(ipoId.toString(), token(userId))
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.message").isEqualTo("대기열에 진입했습니다.")
                .jsonPath("$.data.ipoId").isEqualTo(ipoId.toString())
                .jsonPath("$.data.status").isEqualTo("WAITING")
                .jsonPath("$.data.position").isEqualTo(1)
                .jsonPath("$.data.waitingCount").isEqualTo(1)
                .jsonPath("$.data.estimatedWaitSeconds").isEqualTo(1)
                .jsonPath("$.data.enteredAt").value(value -> assertThat((String) value).endsWith("+09:00"))
                .jsonPath("$.data.admissionExpiresAt").isEmpty()
                .jsonPath("$.data.pollIntervalMillis").isEqualTo(1000);
    }

    @Test
    void 다시_진입하면_200과_처음_순번을_돌려준다() throws Exception {
        enter(ipoId.toString(), token(userId)).expectStatus().isCreated();
        enter(ipoId.toString(), token(UUID.randomUUID())).expectStatus().isCreated();

        enter(ipoId.toString(), token(userId))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.position").isEqualTo(1)
                .jsonPath("$.data.waitingCount").isEqualTo(2);
    }

    @Test
    void 입장_허가_상태에서_진입하면_200과_허가_만료_시각을_돌려준다() throws Exception {
        repository.enter(ipoId, userId, Instant.now(), Instant.now().plusSeconds(60)).block();
        repository.admit(ipoId, Instant.now(), 1, Duration.ofMinutes(5), 1000).block();

        enter(ipoId.toString(), token(userId))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("ADMITTED")
                .jsonPath("$.data.position").isEqualTo(0)
                .jsonPath("$.data.estimatedWaitSeconds").isEqualTo(0)
                .jsonPath("$.data.admissionExpiresAt").value(value ->
                        assertThat((String) value).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?\\+09:00"));
    }

    @Test
    void 대기열_주인은_토큰의_사용자다() throws Exception {
        UUID other = UUID.randomUUID();
        webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", ipoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(userId))
                .header("X-User-Id", other.toString())
                .exchange()
                .expectStatus().isCreated();

        assertThat(TEMPLATE.opsForZSet().score(QueueRedisRepository.waitingKey(ipoId), userId.toString()).block()).isNotNull();
        assertThat(TEMPLATE.opsForZSet().score(QueueRedisRepository.waitingKey(ipoId), other.toString()).block()).isNull();
    }

    @Test
    void 대문자_ipoId로_진입해도_같은_줄이다() throws Exception {
        enter(ipoId.toString(), token(userId)).expectStatus().isCreated();

        enter(ipoId.toString().toUpperCase(), token(userId)).expectStatus().isOk();
    }

    @Test
    void ipoId가_UUID가_아니면_400() throws Exception {
        enter("not-a-uuid", token(userId))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR")
                .jsonPath("$.error.details[0].field").isEqualTo("ipoId");
    }

    @Test
    void 토큰이_없으면_401() {
        webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", ipoId)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void 순서를_조회하면_200과_대기_상태를_돌려준다() throws Exception {
        enter(ipoId.toString(), token(UUID.randomUUID())).expectStatus().isCreated();
        enter(ipoId.toString(), token(userId)).expectStatus().isCreated();

        find(ipoId.toString(), token(userId))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.message").isEqualTo("대기열 순서를 조회했습니다.")
                .jsonPath("$.data.status").isEqualTo("WAITING")
                .jsonPath("$.data.position").isEqualTo(2)
                .jsonPath("$.data.waitingCount").isEqualTo(2)
                .jsonPath("$.data.enteredAt").value(value -> assertThat((String) value).endsWith("+09:00"))
                .jsonPath("$.data.admissionExpiresAt").isEmpty()
                .jsonPath("$.data.pollIntervalMillis").isEqualTo(1000);
    }

    @Test
    void 순서를_조회하면_이탈_기한이_늘어난다() throws Exception {
        enter(ipoId.toString(), token(userId)).expectStatus().isCreated();
        // 이탈 기한을 과거로 돌려 둔다
        TEMPLATE.opsForZSet().add(QueueRedisRepository.heartbeatKey(ipoId), userId.toString(), 0).block();
        long before = Instant.now().toEpochMilli();

        find(ipoId.toString(), token(userId)).expectStatus().isOk();

        // 간격 1초 × 이탈 배수 3
        Double deadline = TEMPLATE.opsForZSet().score(QueueRedisRepository.heartbeatKey(ipoId), userId.toString()).block();
        assertThat(deadline).isBetween(before + 3000.0, Instant.now().toEpochMilli() + 3000.0);
    }

    @Test
    void 입장_허가_상태에서_조회하면_ADMITTED와_허가_만료_시각을_돌려준다() throws Exception {
        repository.enter(ipoId, userId, Instant.now(), Instant.now().plusSeconds(60)).block();
        repository.admit(ipoId, Instant.now(), 1, Duration.ofMinutes(5), 1000).block();

        find(ipoId.toString(), token(userId))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("ADMITTED")
                .jsonPath("$.data.position").isEqualTo(0)
                .jsonPath("$.data.admissionExpiresAt").value(value -> assertThat((String) value).endsWith("+09:00"));
    }

    @Test
    void 줄에_없으면_조회는_404() throws Exception {
        find(ipoId.toString(), token(userId))
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void 다른_공모주_줄에_있어도_이_공모주_조회는_404() throws Exception {
        enter(UUID.randomUUID().toString(), token(userId)).expectStatus().isCreated();

        find(ipoId.toString(), token(userId)).expectStatus().isNotFound();
    }

    @Test
    void 조회도_토큰의_사용자_기준이다() throws Exception {
        enter(ipoId.toString(), token(userId)).expectStatus().isCreated();

        webTestClient.get().uri("/api/v1/queues/{ipoId}/entries/me", ipoId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(UUID.randomUUID()))
                .header("X-User-Id", userId.toString())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void 조회_ipoId가_UUID가_아니면_400() throws Exception {
        find("not-a-uuid", token(userId))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void 조회도_토큰이_없으면_401() {
        webTestClient.get().uri("/api/v1/queues/{ipoId}/entries/me", ipoId)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec find(String ipoIdPath, String token) {
        return webTestClient.get().uri("/api/v1/queues/{ipoId}/entries/me", ipoIdPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private WebTestClient.ResponseSpec enter(String ipoIdPath, String token) {
        return webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", ipoIdPath)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private String token(UUID subject) throws Exception {
        return JwtTestTokens.valid(secret, subject.toString(), "USER");
    }
}
