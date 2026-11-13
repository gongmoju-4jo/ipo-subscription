package com.gongmoju.subscription.presentation.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 관리자 API에만 역할 확인 인터셉터를 건다. 다른 API에는 영향 없음
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor()).addPathPatterns("/api/v1/admin/**");
    }
}
