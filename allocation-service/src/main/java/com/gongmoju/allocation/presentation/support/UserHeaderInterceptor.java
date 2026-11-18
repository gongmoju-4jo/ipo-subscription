package com.gongmoju.allocation.presentation.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 회원 API(/api/v1/allocations/**)의 사용자 헤더 확인 (#191). Gateway가 JWT를 검증하고 넣어 주는 X-User-Id가 없거나 UUID가 아니면 401.
 * <p>
 * 컨트롤러 메서드 안에서 확인하면 경로 값 변환이 먼저 돌아, 헤더 없는 요청에 400과 필드별 사유가 나간다.
 * 그래서 컨트롤러로 가기 전인 인터셉터에서 확인한다 (청약, 계좌 서비스와 같은 규칙).
 * 예외는 GlobalExceptionHandler가 공통 형식(401 UNAUTHORIZED)으로 응답한다.
 */
public class UserHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestHeaders.userId(request.getHeader(RequestHeaders.USER_ID));
        return true;
    }
}
