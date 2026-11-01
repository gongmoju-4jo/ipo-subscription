package com.gongmoju.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Gateway가 직접 내려주는 오류 응답. 서비스와 같은 공통 오류 형식(API 명세 1.5)으로 만든다.
 * Gateway는 common을 쓰지 않아서 이 형식을 여기서 직접 만든다. details는 서비스의 공통 응답처럼 없으면 생략한다.
 */
final class ErrorResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ErrorResponses() {
    }

    // {"success":false,"error":{"code":"...","message":"..."}}
    record ApiError(String code, String message) {
    }

    record Body(boolean success, ApiError error) {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        byte[] bytes = serialize(new Body(false, new ApiError(code, message)));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private static byte[] serialize(Body body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
