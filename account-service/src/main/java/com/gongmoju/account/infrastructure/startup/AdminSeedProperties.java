package com.gongmoju.account.infrastructure.startup;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첫 관리자 시드 설정 (application.yml의 admin-seed.*).
 * <p>
 * @Value로 읽지 않는다. @Value는 값 안의 #{...}를 SpEL로 계산해서, ADMIN_PASSWORD=aa#{12345678}이 aa12345678로 저장된다.
 * 다만 ${...}는 이 방식에서도 다른 설정 이름으로 해석되므로 값에 $를 쓰지 않는다 (deploy.sh가 막는다).
 *
 * @param loginId  첫 관리자 로그인 ID (ADMIN_LOGIN_ID). 비어 있으면 시드하지 않는다
 * @param password 첫 관리자 비밀번호 원문 (ADMIN_PASSWORD). 저장, 로그에 남기지 않는다
 */
@ConfigurationProperties(prefix = "admin-seed")
public record AdminSeedProperties(String loginId, String password) {

    // 비밀번호가 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "AdminSeedProperties[loginId=" + loginId + ", password=****]";
    }
}
