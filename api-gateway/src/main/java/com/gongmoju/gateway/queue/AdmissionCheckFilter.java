package com.gongmoju.gateway.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 청약 신청(POST /api/v1/subscriptions)은 그 공모주의 입장 허가가 있을 때만 서비스로 보낸다.
 *
 * <ul>
 *   <li>본문의 ipoId만 읽어 허가를 확인하고, 본문은 바꾸지 않고 서비스로 보낸다. X-Request-Received-At(마감 판단 기준)은
 *       인증보다 먼저 기록된 값 그대로라 허가 확인 시간이 섞이지 않는다</li>
 *   <li>허가 없음 → 403 QUEUE_ADMISSION_REQUIRED. 다른 공모주의 허가로는 통과하지 못한다 (본문의 ipoId 기준)</li>
 *   <li>본문이 JSON이 아니거나 ipoId가 UUID가 아니면 확인 없이 보낸다. 서비스가 같은 본문을 받아 400을 준다.
 *       서비스와 같은 Jackson, 같은 문자셋 처리로 읽어서 게이트웨이가 못 읽는 본문을 서비스가 읽는 경우가 없게 한다</li>
 *   <li>같은 키가 두 번 이상 있는 본문 → 400 VALIDATION_ERROR (서비스가 어느 ipoId로 접수할지 키 순서에 따라 달라져서 보내지 않는다)</li>
 *   <li>본문이 {@link #MAX_BODY_BYTES}를 넘으면 400 VALIDATION_ERROR</li>
 *   <li>메서드는 대소문자를 가리지 않고, 경로는 퍼센트 디코딩 후 비교한다. 끝 슬래시도 확인 대상이다</li>
 *   <li>Redis 실패 → 503. 허가 확인 없이 통과시키지 않는다</li>
 * </ul>
 * 라우팅 직전(GlobalFilter)에 실행되므로 인증(401)은 이미 끝난 뒤다.
 */
@Slf4j
@Component
public class AdmissionCheckFilter implements GlobalFilter, Ordered {

    // 청약 신청 본문은 200바이트 안팎이다. 본문을 메모리에 읽어 두므로 큰 본문으로 메모리를 쓰지 못하게 상한을 둔다
    static final int MAX_BODY_BYTES = 16 * 1024;

    // 경로 비교는 서비스와 같은 방식(퍼센트 디코딩 후 세그먼트 비교)으로 한다. 문자열로 비교하면 /api/v1/%73ubscriptions가 빠져나간다
    private static final List<PathPattern> SUBSCRIBE_PATHS = List.of(
            PathPatternParser.defaultInstance.parse("/api/v1/subscriptions"),
            PathPatternParser.defaultInstance.parse("/api/v1/subscriptions/"));

    // Spring MVC Jackson 변환기가 바이트를 그대로 Jackson에 넘기는(인코딩 자동 감지) 문자셋 (AbstractJackson2HttpMessageConverter.readJavaType)
    private static final Set<String> JACKSON_DETECTED_CHARSETS =
            Set.of("UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE", "US-ASCII", "UTF-16", "UTF-32");

    private final QueueRedisRepository repository;
    private final ObjectMapper objectMapper;

    public AdmissionCheckFilter(QueueRedisRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
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
        // 메서드 이름은 대소문자를 가리지 않고 비교한다 (post로 보내 확인을 건너뛰지 못하게)
        if (!HttpMethod.POST.name().equalsIgnoreCase(request.getMethod().name()) || !isSubscribePath(request)) {
            return chain.filter(exchange);
        }
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(AdmissionCheckFilter::toBytes)
                .defaultIfEmpty(new byte[0])
                .flatMap(body -> check(exchange, chain, body))
                .onErrorResume(DataBufferLimitException.class, e ->
                        write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 본문이 너무 큽니다."));
    }

    private Mono<Void> check(ServerWebExchange exchange, GatewayFilterChain chain, byte[] body) {
        ServerWebExchange replayable = exchange.mutate().request(replay(exchange.getRequest(), body)).build();
        BodyCheck result = inspect(exchange.getRequest(), body);
        if (result.duplicateKey()) {
            return write(exchange, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "요청 본문에 같은 키가 두 번 이상 있습니다.");
        }
        if (result.ipoId() == null) {
            return chain.filter(replayable);
        }
        return userId().flatMap(userId -> repository.isAdmitted(result.ipoId(), userId))
                .defaultIfEmpty(false)
                .flatMap(admitted -> admitted
                        ? chain.filter(replayable)
                        : write(exchange, HttpStatus.FORBIDDEN, "QUEUE_ADMISSION_REQUIRED",
                                "대기열 입장 허가가 필요합니다. 대기열에 진입해주세요."))
                .onErrorResume(DataAccessException.class, e -> {
                    log.warn("입장 허가 확인 실패 (Redis): {}", e.toString());
                    return write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                            "서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.");
                });
    }

    private static boolean isSubscribePath(ServerHttpRequest request) {
        return SUBSCRIBE_PATHS.stream().anyMatch(pattern -> pattern.matches(request.getPath().pathWithinApplication()));
    }

    /**
     * 본문을 서비스(Spring MVC의 Jackson 변환기)와 같은 방식으로 읽어 ipoId를 꺼낸다.
     * 게이트웨이가 못 읽는 본문을 서비스가 읽어 허가 없이 접수되는 일이 없게, 문자셋 처리도 서비스와 같게 한다:
     * Content-Type 문자셋이 없거나 UTF-8/16/32, US-ASCII면 바이트를 Jackson이 자동 감지하고, 그 밖의 문자셋(예: IBM037)이면 그 문자셋으로 풀어 읽는다.
     */
    private BodyCheck inspect(ServerHttpRequest request, byte[] body) {
        Charset charset;
        try {
            MediaType contentType = request.getHeaders().getContentType();
            charset = contentType == null ? null : contentType.getCharset();
        } catch (IllegalArgumentException e) {
            // Content-Type을 해석할 수 없으면 서비스도 같은 파서로 실패한다 (415)
            return BodyCheck.UNREADABLE;
        }
        JsonNode root;
        try {
            root = read(objectMapper.reader(), body, charset);
        } catch (IOException e) {
            return BodyCheck.UNREADABLE;
        }
        // 같은 키가 두 번 나오면 서비스(record 생성자 바인딩)가 어느 값을 쓸지 키 순서에 따라 달라진다. 확인한 공모주와 접수될 공모주가
        // 어긋나지 않게 중복 키 본문은 서비스로 보내지 않는다
        try {
            read(objectMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION), body, charset);
        } catch (IOException e) {
            return BodyCheck.DUPLICATE_KEY;
        }
        if (root == null) {
            return BodyCheck.UNREADABLE;
        }
        JsonNode value = root.path("ipoId");
        if (!value.isTextual()) {
            return BodyCheck.UNREADABLE;
        }
        try {
            return BodyCheck.of(UUID.fromString(value.asText()));
        } catch (IllegalArgumentException e) {
            return BodyCheck.UNREADABLE;
        }
    }

    private static JsonNode read(ObjectReader reader, byte[] body, Charset charset) throws IOException {
        if (charset == null || JACKSON_DETECTED_CHARSETS.contains(charset.name())) {
            return reader.readTree(body);
        }
        return reader.readTree(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }

    /**
     * 본문 확인 결과. ipoId가 있으면 허가를 확인하고, 읽을 수 없으면(서비스도 같은 방식으로 실패) 확인 없이 보내고, 중복 키면 막는다.
     */
    private record BodyCheck(UUID ipoId, boolean duplicateKey) {
        static final BodyCheck UNREADABLE = new BodyCheck(null, false);
        static final BodyCheck DUPLICATE_KEY = new BodyCheck(null, true);

        static BodyCheck of(UUID ipoId) {
            return new BodyCheck(ipoId, false);
        }
    }

    // 인증된 토큰의 sub (API 체인이 인증을 요구하므로 여기까지 오면 있다). 없거나 UUID가 아니면 허가 없음으로 본다
    private static Mono<UUID> userId() {
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(context -> Mono.justOrEmpty(context.getAuthentication()))
                .filter(JwtAuthenticationToken.class::isInstance)
                .flatMap(authentication -> {
                    try {
                        return Mono.just(UUID.fromString(((JwtAuthenticationToken) authentication).getToken().getSubject()));
                    } catch (IllegalArgumentException | NullPointerException e) {
                        return Mono.empty();
                    }
                });
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    // 이미 읽은 본문을 서비스로 다시 보낼 수 있게 한다 (요청 본문은 한 번만 읽힌다)
    private static ServerHttpRequest replay(ServerHttpRequest request, byte[] body) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return body.length == 0 ? Flux.empty() : Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)));
            }
        };
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
