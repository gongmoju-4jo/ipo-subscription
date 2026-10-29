package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// 클라이언트가 보낸 신뢰 헤더를 지우고, Gateway가 기록한 도착 시각을 붙이는지 확인한다 (서비스 없이 필터만 실행)
class RequestHeaderFilterTest {

    private static final String RECEIVED_AT = "X-Request-Received-At";

    private final AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    private final AtomicInteger chainCalls = new AtomicInteger();
    private final WebFilterChain chain = exchange -> {
        forwarded.set(exchange);
        chainCalls.incrementAndGet();
        return Mono.empty();
    };

    @Test
    void 클라이언트가_보낸_X_User_Id와_X_User_Role은_서비스에_전달되지_않는다() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-User-Id", "11111111-1111-4111-8111-111111111111")
                .header("X-User-Role", "ADMIN")
                .build();

        filterAt("2026-11-02T00:59:59.123Z", request);

        assertThat(forwardedHeaders().containsKey("X-User-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-User-Role")).isFalse();
    }

    @Test
    void 헤더_이름의_대소문자가_달라도_같은_헤더로_보고_모두_지운다() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("x-user-id", "attacker")
                .header("X-USER-ROLE", "ADMIN")
                .build();

        filterAt("2026-11-02T00:59:59.123Z", request);

        assertThat(forwardedHeaders().containsKey("X-User-Id")).isFalse();
        assertThat(forwardedHeaders().containsKey("X-User-Role")).isFalse();
    }

    @Test
    void 같은_헤더를_여러_번_보내도_전부_지운다() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("X-User-Id", "a", "b")
                .build();

        filterAt("2026-11-02T00:59:59.123Z", request);

        assertThat(forwardedHeaders().containsKey("X-User-Id")).isFalse();
    }

    @Test
    void 도착_시각은_Gateway_시각의_UTC_ISO_8601_밀리초로_기록한다() {
        filterAt("2026-11-02T00:59:59.123Z", MockServerHttpRequest.get("/api/v1/ipos").build());

        assertThat(forwardedHeaders().getFirst(RECEIVED_AT)).isEqualTo("2026-11-02T00:59:59.123Z");
    }

    @Test
    void 클라이언트가_보낸_도착_시각은_무시하고_Gateway_시각으로_덮어쓴다() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/subscriptions")
                .header(RECEIVED_AT, "2026-11-01T00:00:00.000Z")
                .build();

        filterAt("2026-11-02T00:59:59.123Z", request);

        assertThat(forwardedHeaders().get(RECEIVED_AT)).containsExactly("2026-11-02T00:59:59.123Z");
    }

    @Test
    void 도착_시각은_밀리초_3자리를_항상_채운다() {
        // 정각 초, 소수부가 짧은 값, 나노초가 있는 값 (3자리 아래는 버린다)
        filterAt("2026-11-02T00:59:59Z", MockServerHttpRequest.get("/api/v1/ipos").build());
        assertThat(forwardedHeaders().getFirst(RECEIVED_AT)).isEqualTo("2026-11-02T00:59:59.000Z");

        filterAt("2026-11-02T00:59:59.100Z", MockServerHttpRequest.get("/api/v1/ipos").build());
        assertThat(forwardedHeaders().getFirst(RECEIVED_AT)).isEqualTo("2026-11-02T00:59:59.100Z");

        filterAt("2026-11-02T00:59:59.123987654Z", MockServerHttpRequest.get("/api/v1/ipos").build());
        assertThat(forwardedHeaders().getFirst(RECEIVED_AT)).isEqualTo("2026-11-02T00:59:59.123Z");
    }

    @Test
    void 인증이_필요_없는_요청에도_도착_시각을_붙인다() {
        filterAt("2026-11-02T00:59:59.123Z", MockServerHttpRequest.post("/api/v1/auth/login").build());

        assertThat(forwardedHeaders().getFirst(RECEIVED_AT)).isEqualTo("2026-11-02T00:59:59.123Z");
    }

    @Test
    void 다른_헤더는_그대로_전달하고_다음_필터를_한_번_호출한다() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/accounts")
                .header("Authorization", "Bearer token")
                .header("Content-Type", "application/json")
                .build();

        filterAt("2026-11-02T00:59:59.123Z", request);

        assertThat(forwardedHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
        assertThat(forwardedHeaders().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(chainCalls).hasValue(1);
    }

    private void filterAt(String instant, MockServerHttpRequest request) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        new RequestHeaderFilter(clock).filter(MockServerWebExchange.from(request), chain).block();
    }

    private HttpHeaders forwardedHeaders() {
        return forwarded.get().getRequest().getHeaders();
    }
}
