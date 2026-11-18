package com.gongmoju.subscription.presentation.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 회원 API(/api/v1/subscriptions/**)의 사용자 헤더 확인 (#191). Gateway가 JWT를 검증하고 넣어 주는 X-User-Id가 없거나 UUID가 아니면 401.
 * 청약 신청(POST /api/v1/subscriptions)은 마감 판단 기준인 X-Request-Received-At도 확인한다 (조회 API는 이 헤더를 쓰지 않아 확인하지 않음).
 * <p>
 * 컨트롤러 메서드 안에서 확인하면 요청 본문 검사(@Valid)와 경로, 쿼리 값 변환이 먼저 돌아, 헤더 없는 요청에 400과 필드별 사유가 나간다.
 * 그래서 본문을 읽기 전인 인터셉터에서 확인한다 (관리자 API의 {@link AdminRoleInterceptor}와 같은 이유).
 * 확인 규칙은 컨트롤러와 같은 {@link RequestHeaders} 함수를 써서, 여기를 통과한 헤더는 컨트롤러에서도 통과한다.
 * 예외는 GlobalExceptionHandler가 공통 형식(401 UNAUTHORIZED)으로 응답한다.
 */
public class UserHeaderInterceptor implements HandlerInterceptor {

    static final String SUBSCRIBE_PATTERN = "/api/v1/subscriptions";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestHeaders.userId(request.getHeader(RequestHeaders.USER_ID));
        if (isSubscribe(request)) {
            RequestHeaders.receivedAt(request.getHeader(RequestHeaders.RECEIVED_AT));
        }
        return true;
    }

    // 요청 주소 문자열 대신 실제로 맞은 매핑 경로로 판단한다 (인코딩된 주소, 끝 슬래시와 관계없이 청약 신청 메서드로 가는 요청만)
    private static boolean isSubscribe(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && SUBSCRIBE_PATTERN.equals(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    }
}
