package com.gongmoju.account.domain.auth;

// 발급한 액세스 토큰. expiresIn은 토큰이 만료될 때까지 남은 시간(초)으로 로그인 응답의 expiresIn과 같다
public record AccessToken(String value, long expiresIn) {

    // 토큰은 인증 수단이라 로그에 남지 않게 가린다
    @Override
    public String toString() {
        return "AccessToken[value=****, expiresIn=" + expiresIn + "]";
    }
}
