package com.gongmoju.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 발행기와 {@link Outbox} 엔티티, {@link OutboxJpaRepository}를 서비스에 등록한다.
 * <p>
 * 서비스는 자기 패키지만 스캔하므로, 이 패키지를 엔티티, Repository 스캔 대상에 더한다({@link AutoConfigurationPackage}).
 * 서비스의 기존 스캔은 그대로 유지된다 (자동 설정 패키지에 더하는 방식이라 서비스가 {@code @EnableJpaRepositories}를 직접 붙이지 않는 한 바뀌지 않는다).
 * Repository 스캔이 패키지 등록보다 먼저 일어나지 않도록 JPA 자동 설정보다 앞에 둔다.
 * <p>
 * 표는 서비스마다 자기 스키마의 {@code outbox}를 쓰고(서비스별 {@code hibernate.default_schema}), 체크 제약과 부분 인덱스는 서비스의 schema.sql에 둔다.
 * 발행기와 메시지 팩토리는 이 패키지 안에서만 쓰므로 package-private으로 두고, 같은 패키지인 이 클래스에서 등록한다.
 * <p>
 * Kafka나 JPA가 없는 앱(gateway)에서는 등록하지 않는다
 */
@AutoConfiguration(before = {JpaRepositoriesAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ConditionalOnClass({KafkaTemplate.class, EntityManagerFactory.class})
@AutoConfigurationPackage(basePackageClasses = Outbox.class)
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class CommonOutboxAutoConfiguration {

    /**
     * 메시지 팩토리를 등록한다.
     */
    @Bean
    OutboxMessageFactory outboxMessageFactory(ObjectMapper objectMapper) {
        return new OutboxMessageFactory(objectMapper);
    }

    /**
     * 발행기를 등록한다. 스케줄링은 아래 {@link SchedulingConfig}가 켠다.
     */
    @Bean
    OutboxPublisher outboxPublisher(OutboxJpaRepository outboxRepository,
                                    OutboxMessageFactory messageFactory,
                                    KafkaTemplate<String, String> kafkaTemplate,
                                    TransactionTemplate transactionTemplate,
                                    OutboxPublisherProperties properties) {
        return new OutboxPublisher(outboxRepository, messageFactory, kafkaTemplate, transactionTemplate, properties);
    }

    /**
     * 스케줄링은 {@code outbox.publisher.enabled=false}로 끌 수 있다 (테스트는 발행 메서드를 직접 호출한다)
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "outbox.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
