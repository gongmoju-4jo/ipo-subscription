package com.gongmoju.subscription.presentation.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 관리자 API에는 역할 확인, 회원 API에는 사용자 헤더 확인 인터셉터를 건다 (#191).
// 공모주 조회(/api/v1/ipos/**)는 회원 ID를 쓰지 않고, /internal/**은 서비스 간 호출이라 사용자 헤더가 없어 걸지 않는다
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor()).addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(new UserHeaderInterceptor()).addPathPatterns("/api/v1/subscriptions/**");
    }
}
