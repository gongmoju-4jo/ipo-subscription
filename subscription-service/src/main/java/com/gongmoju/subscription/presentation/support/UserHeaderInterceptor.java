package com.gongmoju.subscription.presentation.support;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 회원 API(/api/v1/subscriptions/**)의 사용자 헤더 확인 (#191). Gateway가 JWT를 검증하고 넣어 주는 X-User-Id가 없거나 UUID가 아니면 401.
 * 마감 판단 기준인 X-Request-Received-At은 그 헤더를 받는 API(지금은 청약 신청)만 확인한다 (조회 API는 이 헤더를 쓰지 않음).
 * <p>
 * 컨트롤러 메서드 안에서 확인하면 요청 본문 검사(@Valid)와 경로, 쿼리 값 변환이 먼저 돌아, 헤더 없는 요청에 400과 필드별 사유가 나간다.
 * 그래서 본문을 읽기 전인 인터셉터에서 확인한다 (관리자 API의 {@link AdminRoleInterceptor}와 같은 이유).
 * 확인 규칙은 컨트롤러와 같은 {@link RequestHeaders} 함수를 써서, 여기를 통과한 헤더는 컨트롤러에서도 통과한다.
 * 예외는 GlobalExceptionHandler가 공통 형식(401 UNAUTHORIZED)으로 응답한다.
 */
public class UserHeaderInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RequestHeaders.userId(request.getHeader(RequestHeaders.USER_ID));
        if (readsReceivedAt(handler)) {
            RequestHeaders.receivedAt(request.getHeader(RequestHeaders.RECEIVED_AT));
        }
        return true;
    }

    // 경로를 따로 적어 두지 않고, 요청이 가는 컨트롤러 메서드가 X-Request-Received-At 헤더 파라미터를 받는지로 판단한다
    // (매핑 경로가 바뀌거나 이 헤더를 쓰는 API가 늘어도 그대로 맞음). 컨트롤러로 가지 않는 요청(없는 경로)은 확인하지 않는다
    private static boolean readsReceivedAt(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        // Spring이 어노테이션을 합성해 주므로 value와 name 중 어느 쪽으로 적어도 value()로 읽힌다
        return Arrays.stream(method.getMethodParameters())
                .map(parameter -> parameter.getParameterAnnotation(RequestHeader.class))
                .anyMatch(header -> header != null && RequestHeaders.RECEIVED_AT.equals(header.value()));
    }
}
