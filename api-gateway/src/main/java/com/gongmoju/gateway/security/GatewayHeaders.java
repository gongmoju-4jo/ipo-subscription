package com.gongmoju.gateway.security;

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

    private GatewayHeaders() {
    }
}
