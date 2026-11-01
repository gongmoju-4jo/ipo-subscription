package com.gongmoju.gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 모든 요청에서 가장 먼저 실행되어, 클라이언트가 보낸 신뢰 헤더를 지우고 도착 시각을 기록한다.
 * 서비스는 X-User-Id, X-User-Role, X-Request-Received-At를 믿기 때문에 클라이언트 값이 그대로 전달되면 안 된다.
 * 공개 경로를 포함해 Gateway를 거치는 모든 요청에 도착 시각을 붙이고, 인증 처리 시간이 섞이지 않게 인증보다 먼저 기록한다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestHeaderFilter implements WebFilter {

    // 밀리초 3자리를 항상 채운다 (Instant.toString()은 정각 초에서 소수부를 생략한다)
    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final Clock clock;

    @Autowired
    public RequestHeaderFilter() {
        this(Clock.systemUTC());
    }

    public RequestHeaderFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String receivedAt = RECEIVED_AT_FORMAT.format(clock.instant());
        ServerWebExchange sanitized = exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(GatewayHeaders.USER_ID);
                    headers.remove(GatewayHeaders.USER_ROLE);
                    headers.set(GatewayHeaders.REQUEST_RECEIVED_AT, receivedAt);
                }))
                .build();
        return chain.filter(sanitized);
    }
}
