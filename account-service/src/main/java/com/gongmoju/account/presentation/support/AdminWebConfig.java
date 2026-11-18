package com.gongmoju.account.presentation.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 관리자 API에는 역할 확인, 회원 API에는 사용자 헤더 확인 인터셉터를 건다 (#191).
// 회원가입(/api/v1/users), 로그인(/api/v1/auth/**)은 토큰 없이 부르는 공개 API라 걸지 않는다
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor()).addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(new UserHeaderInterceptor()).addPathPatterns("/api/v1/accounts/**");
    }
}
