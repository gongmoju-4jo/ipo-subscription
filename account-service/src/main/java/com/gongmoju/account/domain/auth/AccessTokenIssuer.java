package com.gongmoju.account.domain.auth;

import com.gongmoju.common.auth.UserRole;

import java.util.UUID;

// 액세스 토큰 발급. 구현은 infrastructure/security (JWT)
public interface AccessTokenIssuer {

    /**
     * 회원의 액세스 토큰을 발급한다.
     *
     * @param userId 회원 ID (토큰의 sub)
     * @param role   회원 역할
     * @return 발급한 토큰과 만료까지 남은 시간
     */
    AccessToken issue(UUID userId, UserRole role);
}
