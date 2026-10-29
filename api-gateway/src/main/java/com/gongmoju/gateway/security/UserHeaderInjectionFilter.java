package com.gongmoju.gateway.security;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * JWT 인증이 끝난 요청에 검증한 토큰의 sub를 X-User-Id로, role을 X-User-Role로 설정한다.
 * 서비스는 이 헤더로 요청자를 판단한다. 클라이언트가 보낸 값은 가장 먼저 {@link RequestHeaderFilter}가 지웠고,
 * 토큰 인증이 없는 요청(공개 경로)에는 이 헤더를 설정하지 않는다.
 * 인증 결과가 있어야 해서 보안 필터 체인의 인증 단계 뒤에 등록한다 (빈으로 등록하지 않는다. {@link SecurityConfig} 참고).
 */
public class UserHeaderInjectionFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(authentication -> withUserHeaders(exchange, authentication.getToken()))
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, Jwt token) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.set(GatewayHeaders.USER_ID, token.getSubject());
                    headers.set(GatewayHeaders.USER_ROLE, token.getClaimAsString("role"));
                }))
                .build();
    }
}
