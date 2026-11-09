package com.gongmoju.common.event;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * {@link ProcessedEvent} 엔티티, Repository와 {@link ProcessedEventRepository} 구현을 서비스에 등록한다.
 * 스캔 대상에 더하는 방식과 순서는 {@code CommonOutboxAutoConfiguration}과 같다.
 * 표는 서비스마다 자기 스키마의 {@code processed_event}를 쓴다 (서비스별 {@code hibernate.default_schema}).
 */
@AutoConfiguration(before = {JpaRepositoriesAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ConditionalOnClass(EntityManagerFactory.class)
@AutoConfigurationPackage(basePackageClasses = ProcessedEvent.class)
public class CommonProcessedEventAutoConfiguration {

    /**
     * 기본 구현(JPA)을 등록한다. 서비스나 테스트가 같은 타입의 빈을 직접 등록하면 등록하지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean
    ProcessedEventRepository processedEventRepository(ProcessedEventJpaRepository jpaRepository) {
        return new ProcessedEventRepositoryAdapter(jpaRepository);
    }
}
