package com.gongmoju.account.application.user;

import com.gongmoju.account.domain.user.User;

import java.time.Instant;
import java.util.UUID;

// 가입 결과. 비밀번호(해시 포함)는 담지 않는다
public record SignUpResult(UUID userId, String loginId, String name, Instant createdAt) {

    static SignUpResult from(User user) {
        return new SignUpResult(user.getId(), user.getLoginId(), user.getName(), user.getCreatedAt());
    }
}
