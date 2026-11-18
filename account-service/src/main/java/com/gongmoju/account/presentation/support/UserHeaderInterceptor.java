package com.gongmoju.account.presentation.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 회원 API(/api/v1/accounts/**)의 사용자 헤더 확인 (#191). Gateway가 JWT를 검증하고 넣어 주는 X-User-Id가 없거나 UUID가 아니면 401.
 * <p>
 * 컨트롤러 메서드 안에서 확인하면 요청 본문 검사(@Valid)와 경로, 쿼리 값 변환이 먼저 돌아, 헤더 없는 요청에 400과 필드별 사유가 나간다.
 * 그래서 본문을 읽기 전인 인터셉터에서 확인한다 (관리자 API의 {@link AdminRoleInterceptor}와 같은 이유, 청약 서비스와 같은 규칙).
 * 확인 규칙은 컨트롤러와 같은 {@link RequestUser#userId}를 써서, 여기를 통과한 헤더는 컨트롤러에서도 통과한다.
 * 예외는 GlobalExceptionHandler가 공통 형식(401 UNAUTHORIZED)으로 응답한다.
 */
public class UserHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestUser.userId(request.getHeader(RequestUser.HEADER));
        return true;
    }
}
