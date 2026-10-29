package com.gongmoju.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 인증에 실패한 요청에 401과 공통 오류 응답을 내려준다.
 * 토큰이 만료된 경우만 TOKEN_EXPIRED이고, 토큰이 없거나 위조, 형식 오류 등은 이유를 알리지 않고 UNAUTHORIZED로 같게 응답한다.
 */
public class JsonAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        if (isExpired(exception)) {
            return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "토큰이 만료되었습니다.");
        }
        return ErrorResponses.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
    }

    // 검증 실패 원인 중에 만료 코드가 있는지 본다 (예외가 감싸져 있어서 원인을 따라간다)
    private static boolean isExpired(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException validation && validation.getErrors().stream()
                    .anyMatch(error -> JwtConfig.EXPIRED_ERROR_CODE.equals(error.getErrorCode()))) {
                return true;
            }
        }
        return false;
    }
}
