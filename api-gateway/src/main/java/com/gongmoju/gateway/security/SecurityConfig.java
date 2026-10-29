package com.gongmoju.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Gateway 보안 설정.
 * Authorization 헤더에 토큰이 있으면 JWT를 검증하고 실패하면 401로 막고, 통과하면 토큰의 sub, role을 서비스로 넘길 헤더로 설정한다.
 * 토큰이 없는 요청은 아직 기존처럼 통과시키고, 이후 커밋에서 공개 경로와 관리자 경로 규칙으로 바꾼다.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                // 인증이 끝난 뒤에 검증한 토큰의 sub, role을 X-User-Id, X-User-Role로 설정한다
                .addFilterAfter(new UserHeaderInjectionFilter(), SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
