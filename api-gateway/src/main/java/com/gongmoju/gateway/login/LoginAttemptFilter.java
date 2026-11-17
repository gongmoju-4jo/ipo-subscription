package com.gongmoju.gateway.login;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.gateway.login.LoginAttemptRedisRepository.Reservation;
import com.gongmoju.gateway.queue.ApiResponse;
import com.gongmoju.gateway.support.JsonBodyReader;
import com.gongmoju.gateway.support.JsonBodyReader.JsonBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로그인(POST /api/v1/auth/login) 시도 횟수 제한. 같은 로그인 ID로 일정 기간 안에 정해진 횟수만큼 실패하면 그 ID의 로그인을 막는다 (429).
 *
 * <ul>
 *   <li>서비스로 보내기 전에 실패 횟수를 미리 올리고(동시에 보낸 요청이 모두 확인을 통과하지 못하게), 응답을 보고 확정한다:
 *       401은 실패로 남기고, 2xx는 횟수를 지우고, 그 밖(400, 5xx, 서비스 호출 오류, 서비스 응답 전에 클라이언트가 끊음)은 되돌린다</li>
 *   <li>차단 중이면 서비스로 보내지 않고 429 TOO_MANY_REQUESTS + Retry-After(남은 초). 남은 횟수는 알려주지 않는다</li>
 *   <li>로그인 ID는 서비스와 같은 방식으로 본문에서 읽는다 ({@link JsonBodyReader}). 같은 키가 두 번 이상이면 400.
 *       loginId, password가 문자열이 아니어도(숫자, 불리언, 배열, 객체) 400. 72바이트를 넘는 비밀번호는 세지 않고 바로 401 (서비스와 같은 응답). 게이트웨이가 센 ID와 서비스가 로그인한 ID가 어긋나 제한을 피하지 못하게 한다.
 *       loginId가 없거나 null이거나 본문을 읽을 수 없으면 세지 않고 보낸다 (서비스가 400을 준다)</li>
 *   <li>Redis 실패, {@code redisTimeout} 초과 → 횟수 확인 없이 보내고 WARN 로그. 그 뒤 {@code redisSkip} 동안은 Redis를 부르지 않는다.
 *       돈과 직결된 청약 신청의 입장 허가 확인(Redis 장애 시 503)과 달리, 로그인 제한은 부가 보호라 Redis 장애가 로그인 장애로 번지지 않게 한다</li>
 *   <li>경로는 퍼센트 디코딩 후 비교하고 끝 슬래시도 대상이다. 메서드는 대소문자를 가리지 않는다</li>
 * </ul>
 */
@Slf4j
@Component
public class LoginAttemptFilter implements GlobalFilter, Ordered {

    // 문자열로 비교하면 토큰을 가진 사용자가 /api/v1/auth/%6Cogin으로 보내 제한 없이 시도할 수 있다 (서비스는 디코딩해 로그인으로 처리)
    private static final List<PathPattern> LOGIN_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/api/v1/auth/login"),
            PathPatternParser.defaultInstance.parse("/api/v1/auth/login/"));

    // 계좌 서비스 AuthService.BCRYPT_MAX_BYTES와 같은 값
    private static final int BCRYPT_MAX_BYTES = 72;

    private final LoginAttemptRedisRepository repository;
    private final LoginAttemptProperties properties;
    private final JsonBodyReader bodyReader;
    private final ObjectMapper objectMapper;
    // Redis 실패 뒤 이 시각(System.nanoTime)까지는 Redis를 부르지 않는다
    private final AtomicLong skipUntilNanos = new AtomicLong(System.nanoTime());

    public LoginAttemptFilter(LoginAttemptRedisRepository repository, LoginAttemptProperties properties,
                              JsonBodyReader bodyReader, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.bodyReader = bodyReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        // 로드밸런서(10150), 라우팅(Integer.MAX_VALUE)보다 먼저
        return 0;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.POST.name().equalsIgnoreCase(request.getMethod().name()) || !isLoginPath(request)) {
            return chain.filter(exchange);
        }
        return bodyReader.readBody(request)
                .flatMap(body -> check(exchange, chain, body))
                .onErrorResume(DataBufferLimitException.class, e ->
                        write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 본문이 너무 큽니다."));
    }

    private Mono<Void> check(ServerWebExchange exchange, GatewayFilterChain chain, byte[] body) {
        ServerWebExchange replayable = exchange.mutate().request(JsonBodyReader.replay(exchange.getRequest(), body)).build();
        JsonBody parsed = bodyReader.parse(exchange.getRequest(), body);
        if (parsed.duplicateKey()) {
            return write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 본문에 같은 키가 두 번 이상 있습니다.");
        }
        if (hasNonText(parsed, "loginId")) {
            return write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "로그인 ID는 문자열이어야 합니다.");
        }
        if (hasNonText(parsed, "password")) {
            return write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "비밀번호는 문자열이어야 합니다.");
        }
        String loginId = parsed.text("loginId");
        if (loginId != null && !loginId.isBlank() && isTooLongPassword(parsed.text("password"))) {
            return write(exchange, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "로그인 ID 또는 비밀번호가 올바르지 않습니다.");
        }
        if (loginId == null || redisSkipped()) {
            return chain.filter(replayable);
        }
        return redisCall(repository.reserve(loginId), "예약")
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(reservation -> {
                    if (reservation.isEmpty()) {
                        // Redis 실패, 시간 초과: 횟수 확인 없이 보낸다. 시간 초과로 포기한 예약이 Redis에서 늦게 실행되면 그 횟수는 남고 만료로 사라진다
                        return chain.filter(replayable);
                    }
                    return reservation.get().allowed()
                            ? forward(replayable, chain, loginId)
                            : tooManyRequests(exchange, reservation.get());
                });
    }

    // 서비스로 보내고 응답 상태로 예약을 확정한다. 확정은 응답을 다 보낸 뒤라 결과에 영향을 주지 않는다.
    // 클라이언트가 응답 전에 끊으면(취소) 완료, 오류 신호가 오지 않아 따로 확정한다. 확정은 한 번만 한다
    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String loginId) {
        AtomicBoolean settled = new AtomicBoolean();
        return chain.filter(exchange)
                .then(Mono.defer(() -> settled.compareAndSet(false, true)
                        ? settle(loginId, exchange.getResponse().getStatusCode())
                        : Mono.<Void>empty()))
                .onErrorResume(e -> settled.compareAndSet(false, true)
                        ? settle(loginId, null).then(Mono.error(e))
                        : Mono.error(e))
                .doOnCancel(() -> {
                    if (settled.compareAndSet(false, true)) {
                        // 응답을 보내기 시작했으면 그 상태로(401이면 실패), 아니면 서비스 응답 전이라 되돌린다.
                        // 401을 받자마자 끊어 횟수를 되돌리지 못하게 응답 상태가 정해졌는지로 나눈다
                        ServerHttpResponse response = exchange.getResponse();
                        settle(loginId, response.isCommitted() ? response.getStatusCode() : null).subscribe();
                    }
                });
    }

    private Mono<Void> settle(String loginId, HttpStatusCode status) {
        Mono<Void> action;
        String name;
        if (status != null && status.value() == HttpStatus.UNAUTHORIZED.value()) {
            action = repository.recordFailure(loginId);
            name = "실패 기록";
        } else if (status != null && status.is2xxSuccessful()) {
            action = repository.clear(loginId);
            name = "성공 정리";
        } else {
            action = repository.release(loginId);
            name = "되돌리기";
        }
        Mono<Void> call = redisCall(action.thenReturn(Boolean.TRUE), name).then();
        // 확정 중에 클라이언트가 끊어도(취소) 시작한 확정은 끝까지 실행한다. 함께 취소되면 5번째 실패의 차단 시간(15분)이
        // 걸리지 않아, 401 헤더만 받고 끊어 차단을 줄일 수 있다. 끊기지 않으면 지금처럼 확정이 끝날 때까지 기다린다
        return Mono.create(sink -> call.subscribe(null, sink::error, sink::success));
    }

    // Redis 호출에 시간 초과를 두고, 실패하면 비우고(통과) 건너뛰기 시간을 건다.
    // Redis가 연결은 받고 답하지 않으면 Lettuce가 연결을 맺는 단계에서 구독한 스레드를 Redis 명령 시간 초과(2초)까지 붙잡는다.
    // 요청 처리 스레드(Netty)가 붙잡히면 시간 초과가 끼어들지 못하고 같은 스레드의 다른 요청도 멈추므로, 별도 스레드에서 구독한다
    private <T> Mono<T> redisCall(Mono<T> call, String name) {
        return call.subscribeOn(Schedulers.boundedElastic())
                .timeout(properties.redisTimeout())
                .onErrorResume(e -> {
                    skipUntilNanos.set(System.nanoTime() + properties.redisSkip().toNanos());
                    log.warn("로그인 시도 제한 {} 실패 (Redis). {} 동안 횟수 확인 없이 로그인을 통과시킵니다: {}",
                            name, properties.redisSkip(), e.toString());
                    return Mono.empty();
                });
    }

    // 서비스(Jackson 기본 설정)는 숫자, 불리언도 문자열로 바꿔 받는다 ({"loginId":1234} → "1234").
    // loginId: 게이트웨이가 세지 않고 보내면 숫자로 된 로그인 ID는 제한 없이 시도할 수 있다.
    // password: 긴 숫자를 보내면 아래 72바이트 확인을 피해 BCrypt 없는 401로 키를 쌓을 수 있다. 그래서 문자열이 아닌 값은 여기서 막는다
    private static boolean hasNonText(JsonBody parsed, String field) {
        if (parsed.root() == null) {
            return false;
        }
        JsonNode value = parsed.root().get(field);
        return value != null && !value.isNull() && !value.isTextual();
    }

    // 계좌 서비스는 72바이트를 넘는 비밀번호를 BCrypt 비교 없이 바로 틀렸다고 본다 (AuthService.BCRYPT_MAX_BYTES, BCrypt가 72바이트까지만 씀).
    // 그대로 보내면 비용이 거의 없는 401마다 실패 키가 15분씩 남아, 같은 Redis를 쓰는 대기열까지 메모리 상한에 닿을 수 있다.
    // 절대 맞을 수 없는 비밀번호라 세지 않고 서비스와 같은 401을 바로 준다 (서비스 규칙이 바뀌면 같이 바꾼다)
    static boolean isTooLongPassword(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    private boolean redisSkipped() {
        return System.nanoTime() - skipUntilNanos.get() < 0;
    }

    private static boolean isLoginPath(ServerHttpRequest request) {
        return LOGIN_PATHS.stream().anyMatch(pattern -> pattern.matches(request.getPath().pathWithinApplication()));
    }

    private Mono<Void> tooManyRequests(ServerWebExchange exchange, Reservation reservation) {
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(reservation.retryAfter())));
        return write(exchange, HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해주세요.");
    }

    // 1초 미만이 남아도 0이 아니라 1초로 알린다 (0이면 바로 다시 시도해 다시 막힌다)
    static long retryAfterSeconds(Duration remaining) {
        long millis = Math.max(0, remaining.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(ApiResponse.failure(code, message, null));
            return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
