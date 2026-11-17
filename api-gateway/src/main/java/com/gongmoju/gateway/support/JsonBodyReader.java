package com.gongmoju.gateway.support;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Set;

/**
 * 게이트웨이가 서비스로 보내기 전에 요청 본문(JSON)의 값을 확인할 때 쓴다 (청약 신청의 ipoId, 로그인의 loginId).
 *
 * <ul>
 *   <li>본문을 메모리에 읽으므로 {@link #MAX_BODY_BYTES}를 넘으면 {@link DataBufferLimitException}으로 끝낸다</li>
 *   <li>서비스(Spring MVC의 Jackson 변환기)와 같은 방식으로 읽는다. 게이트웨이가 못 읽는 본문을 서비스가 읽어 확인 없이 처리되는 일이 없게,
 *       문자셋 처리도 서비스와 같게 한다: Content-Type 문자셋이 없거나 UTF-8/16/32, US-ASCII면 바이트를 Jackson이 자동 감지하고,
 *       그 밖의 문자셋(예: IBM037)이면 그 문자셋으로 풀어 읽는다</li>
 *   <li>같은 키가 두 번 이상 있는 본문은 따로 알린다. 서비스(record 생성자 바인딩)가 어느 값을 쓸지 키 순서에 따라 달라져,
 *       게이트웨이가 확인한 값과 서비스가 쓰는 값이 어긋날 수 있다</li>
 *   <li>읽은 본문은 {@link #replay}로 바꾸지 않고 서비스로 다시 보낸다 (요청 본문은 한 번만 읽힌다)</li>
 * </ul>
 */
@Component
public class JsonBodyReader {

    // 확인 대상 본문(청약 신청, 로그인)은 200바이트 안팎이다. 큰 본문으로 메모리를 쓰지 못하게 상한을 둔다
    public static final int MAX_BODY_BYTES = 16 * 1024;

    // Spring MVC Jackson 변환기가 바이트를 그대로 Jackson에 넘기는(인코딩 자동 감지) 문자셋 (AbstractJackson2HttpMessageConverter.readJavaType)
    private static final Set<String> JACKSON_DETECTED_CHARSETS =
            Set.of("UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE", "US-ASCII", "UTF-16", "UTF-32");

    private final ObjectMapper objectMapper;

    public JsonBodyReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 요청 본문을 모두 읽는다. 본문이 없으면 빈 배열.
     *
     * @return 본문 바이트. {@link #MAX_BODY_BYTES}를 넘으면 {@link DataBufferLimitException} 오류
     */
    public Mono<byte[]> readBody(ServerHttpRequest request) {
        return DataBufferUtils.join(request.getBody(), MAX_BODY_BYTES)
                .map(JsonBodyReader::toBytes)
                .defaultIfEmpty(new byte[0]);
    }

    /**
     * 본문을 JSON으로 읽는다.
     *
     * @return 읽은 결과. 읽을 수 없으면(서비스도 같은 방식으로 실패) {@link JsonBody#UNREADABLE}, 같은 키가 두 번 이상이면 {@link JsonBody#DUPLICATE_KEY}
     */
    public JsonBody parse(ServerHttpRequest request, byte[] body) {
        Charset charset;
        try {
            MediaType contentType = request.getHeaders().getContentType();
            charset = contentType == null ? null : contentType.getCharset();
        } catch (IllegalArgumentException e) {
            // Content-Type을 해석할 수 없으면 서비스도 같은 파서로 실패한다 (415)
            return JsonBody.UNREADABLE;
        }
        JsonNode root;
        try {
            root = read(objectMapper.reader(), body, charset);
        } catch (IOException e) {
            return JsonBody.UNREADABLE;
        }
        try {
            read(objectMapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION), body, charset);
        } catch (IOException e) {
            return JsonBody.DUPLICATE_KEY;
        }
        return root == null ? JsonBody.UNREADABLE : new JsonBody(root, false);
    }

    /**
     * 이미 읽은 본문을 서비스로 다시 보낼 수 있는 요청을 만든다.
     */
    public static ServerHttpRequest replay(ServerHttpRequest request, byte[] body) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                return body.length == 0 ? Flux.empty() : Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body)));
            }
        };
    }

    private static JsonNode read(ObjectReader reader, byte[] body, Charset charset) throws IOException {
        if (charset == null || JACKSON_DETECTED_CHARSETS.contains(charset.name())) {
            return reader.readTree(body);
        }
        return reader.readTree(new InputStreamReader(new ByteArrayInputStream(body), charset));
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

    /**
     * 본문을 읽은 결과.
     *
     * @param root         읽은 JSON (읽을 수 없거나 중복 키면 null)
     * @param duplicateKey 같은 키가 두 번 이상 있음
     */
    public record JsonBody(JsonNode root, boolean duplicateKey) {
        public static final JsonBody UNREADABLE = new JsonBody(null, false);
        public static final JsonBody DUPLICATE_KEY = new JsonBody(null, true);

        /**
         * 최상위 객체의 문자열 값. 읽을 수 없는 본문이거나 값이 문자열이 아니면 null.
         */
        public String text(String field) {
            if (root == null) {
                return null;
            }
            JsonNode value = root.path(field);
            return value.isTextual() ? value.asText() : null;
        }
    }
}
