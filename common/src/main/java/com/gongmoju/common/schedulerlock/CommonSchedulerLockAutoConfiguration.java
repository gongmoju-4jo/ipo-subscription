package com.gongmoju.common.schedulerlock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 인스턴스를 여러 개 띄워도 같은 스케줄러 작업이 한 번에 한 인스턴스에서만 실행되게 하는 락(ShedLock)을 서비스에 등록한다.
 * <p>
 * 락은 {@code @SchedulerLock}을 붙인 메서드에만 걸린다. 붙이지 않은 스케줄러는 지금처럼 인스턴스마다 실행된다
 * (이 설정이 들어와도 각 스케줄러에 붙이기 전까지 동작이 바뀌지 않는다).
 * <p>
 * 락은 서비스 자기 스키마의 {@code shedlock} 표에 한 줄로 남는다 (접속 URL의 {@code currentSchema}). 표는 서비스의 schema.sql이 만든다.
 * 락 만료 판단은 DB 시각으로 한다. 인스턴스(컨테이너)마다 시계가 조금씩 달라도 락이 일찍 풀리거나 늦게 풀리지 않게 하기 위해서다.
 * <p>
 * ShedLock이나 DB가 없는 앱(gateway, ShedLock 의존성을 넣지 않은 서비스)에서는 등록하지 않는다
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({LockProvider.class, JdbcTemplateLockProvider.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
public class CommonSchedulerLockAutoConfiguration {

    /**
     * JDBC 락 저장소를 등록한다. 서비스나 테스트가 {@link LockProvider} 빈을 직접 등록하면 등록하지 않는다.
     */
    @Bean
    @ConditionalOnMissingBean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    /**
     * {@code @SchedulerLock}을 처리하게 켠다.
     * 기본 최대 락 시간(10분)은 메서드에 {@code lockAtMostFor}를 적지 않았을 때만 쓰인다. 인스턴스가 작업 중에 죽으면 이 시간이 지나야 다른 인스턴스가 실행할 수 있다.
     * 스케줄러마다 실행 시간이 달라 메서드에 직접 적는 것을 원칙으로 한다
     */
    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    static class SchedulerLockConfig {
    }
}
