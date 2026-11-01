package com.gongmoju.gateway.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 로그인은 했지만 권한이 없는 요청에 403과 공통 오류 응답을 내려준다. 지금은 ADMIN 전용 경로(/api/v1/admin/**)에서만 발생한다.
 */
public class JsonAccessDeniedHandler implements ServerAccessDeniedHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return ErrorResponses.write(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자 권한이 필요합니다.");
    }
}
