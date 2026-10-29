package com.gongmoju.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Flux;

/**
 * Gateway 보안 설정. 요청은 두 체인 중 먼저 맞는 쪽으로 처리된다.
 * <ul>
 *   <li>공개 체인: 회원가입, 로그인, Swagger. 인증 대상에서 제외해서 토큰을 읽지도 않는다
 *       (만료된 토큰이 붙은 채 로그인해도 401이 되지 않는다).</li>
 *   <li>API 체인: 그 외 모든 요청. JWT를 검증하고 실패하면 401, 통과하면 토큰의 sub, role을 서비스로 넘길 헤더로 설정한다.
 *       /api/v1/admin/**은 ADMIN만 허용하고 일반 회원은 403이다. 401, 403은 공통 오류 응답 형식(JSON)으로 내려준다.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // 공개 경로 (API 명세 1.1). 회원가입, 로그인은 POST 하나씩만 공개하고 같은 경로의 다른 메서드는 인증이 필요하다
    private static final ServerWebExchangeMatcher PUBLIC_PATHS = ServerWebExchangeMatchers.matchers(
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/auth/login"),
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET,
                    "/swagger-ui/**", "/v3/api-docs/**", "/*-service/v3/api-docs"));

    @Bean
    @Order(1)
    public SecurityWebFilterChain publicSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(PUBLIC_PATHS)
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    public SecurityWebFilterChain apiSecurityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(new JsonAuthenticationEntryPoint())
                        .accessDeniedHandler(new JsonAccessDeniedHandler())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter())))
                // 인증이 끝난 뒤에 검증한 토큰의 sub, role을 X-User-Id, X-User-Role로 설정한다
                .addFilterAfter(new UserHeaderInjectionFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .build();
    }

    // 토큰의 role(USER, ADMIN)을 권한(ROLE_USER, ROLE_ADMIN)으로 바꾼다. role 값은 JwtConfig가 이미 USER, ADMIN만 통과시킨다
    private static ReactiveJwtAuthenticationConverter roleConverter() {
        ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                jwt -> Flux.just(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
        return converter;
    }
}
