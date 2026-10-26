package com.gongmoju.common.config;

import com.gongmoju.common.exception.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * common을 의존하는 서비스에 공통 예외 처리와 목록 조회 기본값을 자동 등록한다.
 * <p>
 * 서비스는 {@code com.gongmoju.{서비스}} 패키지만 스캔하므로 common 클래스는 자동 설정으로 등록해야 한다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class CommonWebAutoConfiguration {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 목록 기본값: page=0, size=20(최대 100), 정렬을 지정하지 않으면 createdAt DESC
     *
     * @return Pageable 기본값 설정
     */
    @Bean
    public PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> {
            resolver.setFallbackPageable(PageRequest.of(0, DEFAULT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
            resolver.setMaxPageSize(MAX_PAGE_SIZE);
        };
    }
}
