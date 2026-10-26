package com.gongmoju.common.auth;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;

/**
 * 회원 역할.
 * <p>
 * account-service는 role 컬럼에 이름(EnumType.STRING)으로 저장하고, 나머지 서비스는 X-User-Role 헤더 값을 {@link #from(String)}으로 변환한다.
 * USER = 투자자(기본값), ADMIN = 관리자(F-18 공모주 등록, SHOULD). api-gateway UserRole과 같은 값을 유지한다
 */
public enum UserRole {
    USER,
    ADMIN;

    /**
     * X-User-Role 헤더 값을 역할로 변환한다.
     *
     * @param role X-User-Role 헤더 값
     * @return 회원 역할
     * @throws BusinessException 헤더가 없거나 정의되지 않은 역할이면 권한 없음(403)
     */
    public static UserRole from(String role) {
        if (role == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
