package com.gongmoju.allocation.presentation.support;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Gateway가 JWT 검증 후 넣어 주는 사용자 헤더를 읽는다 (청약 서비스 RequestHeaders와 같은 규칙).
 * 컨트롤러와 {@link UserHeaderInterceptor}가 같이 써서, 인터셉터를 통과한 헤더는 컨트롤러에서도 통과한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestHeaders {

    public static final String USER_ID = "X-User-Id";
    // 관리자 API에서 역할 확인용. 없거나 ADMIN이 아니면 403 (common UserRole.from)
    public static final String USER_ROLE = "X-User-Role";

    /**
     * 헤더가 없거나 UUID가 아니면 Gateway를 거치지 않은 요청으로 보고 401로 응답한다.
     *
     * @param header {@code X-User-Id} 헤더 값
     * @return 요청자 회원 ID
     */
    public static UUID userId(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
