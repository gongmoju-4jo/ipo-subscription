package com.gongmoju.account.presentation.support;

import com.gongmoju.common.auth.UserRole;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 관리자 API(/api/v1/admin/**)의 역할 확인. Gateway가 ADMIN만 통과시키지만, 서비스 포트로 직접 호출하면 Gateway를 거치지 않으므로
 * Gateway가 넣은 X-User-Role을 한 번 더 확인한다 (없거나 ADMIN이 아니면 403). 청약 서비스의 AdminRoleInterceptor와 같은 규칙.
 * <p>
 * 컨트롤러 메서드 안에서 확인하면 요청 본문 검사(@Valid)가 먼저 돌아, 권한 없는 요청에 400과 필드별 사유가 나간다.
 * 그래서 본문을 읽기 전인 인터셉터에서 확인한다. 예외는 GlobalExceptionHandler가 공통 형식(403 FORBIDDEN)으로 응답한다.
 */
public class AdminRoleInterceptor implements HandlerInterceptor {

    public static final String USER_ROLE_HEADER = "X-User-Role";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (UserRole.from(request.getHeader(USER_ROLE_HEADER)) != UserRole.ADMIN) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }
}
