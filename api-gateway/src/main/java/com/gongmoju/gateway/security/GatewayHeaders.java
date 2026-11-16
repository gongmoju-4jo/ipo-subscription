package com.gongmoju.gateway.security;

import java.util.List;

/**
 * Gateway가 서비스로 전달하는 헤더 이름 (API 명세 1.1). 서비스는 이 헤더를 믿으므로 클라이언트가 보낸 값은 제거하고 Gateway가 다시 설정한다.
 */
public final class GatewayHeaders {

    /** 검증한 JWT의 sub (회원 ID) */
    public static final String USER_ID = "X-User-Id";

    /** 검증한 JWT의 role (USER, ADMIN) */
    public static final String USER_ROLE = "X-User-Role";

    /** Gateway에 요청이 처음 도착한 시각 (UTC ISO-8601, 밀리초). 청약 마감 판단 기준 (D-12) */
    public static final String REQUEST_RECEIVED_AT = "X-Request-Received-At";

    /**
     * 클라이언트가 보낸 추적 문맥 헤더 (W3C traceparent, tracestate, baggage / B3 단일 헤더). 지우고 서비스로 보낸다.
     * 게이트웨이는 외부 문맥을 이어받지 않고 새 trace를 시작하며, 서비스로 보낼 때 자기 traceparent를 붙인다
     */
    public static final List<String> CLIENT_TRACE_HEADERS = List.of("traceparent", "tracestate", "baggage", "b3");

    /** B3 여러 헤더 형식의 접두사 (X-B3-TraceId, X-B3-SpanId, X-B3-ParentSpanId, X-B3-Sampled, X-B3-Flags) */
    public static final String CLIENT_B3_HEADER_PREFIX = "X-B3-";

    private GatewayHeaders() {
    }
}
