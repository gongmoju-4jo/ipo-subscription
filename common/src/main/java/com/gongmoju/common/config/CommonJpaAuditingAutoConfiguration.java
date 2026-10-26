package com.gongmoju.common.config;

import com.gongmoju.common.audit.RequestAuditorAware;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA를 쓰는 서비스에 BaseEntity Audit 필드 자동 기록을 등록한다. 서비스마다 같은 규칙을 쓰도록 common에 둔다.
 * <p>
 * JPA가 없는 앱(gateway)과 {@code @WebMvcTest}처럼 JPA를 띄우지 않는 테스트에서는 등록하지 않는다
 */
@AutoConfiguration(after = HibernateJpaAutoConfiguration.class)
@ConditionalOnClass(EntityManagerFactory.class)
@ConditionalOnBean(EntityManagerFactory.class)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class CommonJpaAuditingAutoConfiguration {

    /**
     * created_by, updated_by 값을 정하는 AuditorAware를 등록한다.
     *
     * @param applicationName 시스템 작업일 때 기록할 서비스 이름
     * @return 요청 헤더 기반 AuditorAware
     */
    @Bean
    public AuditorAware<String> auditorAware(@Value("${spring.application.name:unknown}") String applicationName) {
        return new RequestAuditorAware(applicationName);
    }
}
