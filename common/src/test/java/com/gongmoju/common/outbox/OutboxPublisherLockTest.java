package com.gongmoju.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.schedulerlock.CommonSchedulerLockAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Outbox 발행기 빈에 스케줄러 락이 걸려, 인스턴스가 여러 개여도 한 번에 한 인스턴스만 발행하는지 실제 PostgreSQL로 확인함 (#177). Docker 필요.
 * <p>
 * 인스턴스 2개는 같은 DB를 쓰는 애플리케이션 컨텍스트 2개로 흉내 낸다 ({@code CommonSchedulerLockAutoConfigurationTest}와 같은 방식).
 * 발행기는 실제 클래스를 빈으로 등록하고, 미발행 조회만 가짜로 바꿔 실행 횟수를 세고 실행 중인 상태로 붙잡아 둔다.
 */
@Testcontainers
@DisplayName("Outbox 발행기 스케줄러 락 (PostgreSQL)")
class OutboxPublisherLockTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** 서비스 schema.sql의 shedlock 표와 같은 정의 */
    private static final String CREATE_SHEDLOCK_TABLE = """
            CREATE TABLE IF NOT EXISTS shedlock (
                name       VARCHAR(64)  NOT NULL,
                lock_until TIMESTAMP    NOT NULL,
                locked_at  TIMESTAMP    NOT NULL,
                locked_by  VARCHAR(255) NOT NULL,
                PRIMARY KEY (name)
            )""";

    /** 두 인스턴스가 함께 세는 실행 횟수 (실행마다 미발행 조회를 한 번 함) */
    static final AtomicInteger runs = new AtomicInteger();
    /** 실행 중인 발행을 붙잡아 두어 두 인스턴스의 실행 시점을 겹치게 한다 */
    static volatile CountDownLatch release = new CountDownLatch(0);
    static volatile CountDownLatch started = new CountDownLatch(1);

    private JdbcTemplate jdbc;

    private ApplicationContextRunner instance() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, AopAutoConfiguration.class, CommonSchedulerLockAutoConfiguration.class))
                .withUserConfiguration(PublisherConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword());
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute(CREATE_SHEDLOCK_TABLE);
        jdbc.update("DELETE FROM shedlock");
        runs.set(0);
        release = new CountDownLatch(0);
        started = new CountDownLatch(1);
    }

    @Test
    @DisplayName("한 인스턴스가 발행하는 동안 다른 인스턴스의 발행은 건너뛰고, 락은 OutboxPublisher.publishPending 이름으로 1분 동안 잡힌다")
    void whilePublishingOnOneInstance_otherInstanceSkips() {
        instance().run(instanceA -> instance().run(instanceB -> {
            // given: A가 발행을 시작해 붙잡혀 있는 동안
            OutboxPublisher publisherA = instanceA.getBean(OutboxPublisher.class);
            assertThat(AopUtils.isAopProxy(publisherA)).isTrue();
            release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> runningOnA = pool.submit(publisherA::publishPending);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            // then: 락 줄의 이름과 시간 (lockAtMostFor 기본값 PT1M이 적용됨)
            assertThat(lockSeconds("OutboxPublisher.publishPending")).isEqualTo(60);

            // when: B가 발행하면
            instanceB.getBean(OutboxPublisher.class).publishPending();
            release.countDown();
            runningOnA.get(10, TimeUnit.SECONDS);
            pool.shutdown();

            // then: B는 락을 얻지 못해 조회도 하지 않고, 실행은 A의 한 번뿐이다
            assertThat(runs.get()).isEqualTo(1);
        }));
    }

    @Test
    @DisplayName("앞 발행이 끝나면 바로 락이 풀려 다른 인스턴스가 이어서 발행한다 (lockAtLeastFor 없음, 발행 지연이 늘지 않음)")
    void afterPublishingFinishes_otherInstancePublishesImmediately() {
        instance().run(instanceA -> instance().run(instanceB -> {
            // given
            instanceA.getBean(OutboxPublisher.class).publishPending();

            // when
            instanceB.getBean(OutboxPublisher.class).publishPending();

            // then
            assertThat(runs.get()).isEqualTo(2);
        }));
    }

    @Test
    @DisplayName("lock-at-most-for 설정을 바꾸면 락 시간도 바뀐다 (어노테이션이 설정 키를 읽음)")
    void lockAtMostFor_followsProperty() {
        instance().withPropertyValues("outbox.publisher.lock-at-most-for=PT2M").run(context -> {
            // given: 발행을 붙잡아 둔 상태
            release = new CountDownLatch(1);
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<?> running = pool.submit(context.getBean(OutboxPublisher.class)::publishPending);
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

            // then
            assertThat(lockSeconds("OutboxPublisher.publishPending")).isEqualTo(120);
            release.countDown();
            running.get(10, TimeUnit.SECONDS);
            pool.shutdown();
        });
    }

    private Long lockSeconds(String name) {
        return jdbc.queryForObject(
                "SELECT round(extract(epoch FROM lock_until - locked_at)) FROM shedlock WHERE name = ?", Long.class, name);
    }

    /** 실제 발행기 클래스. 미발행 조회가 실행 횟수를 세고 release가 열릴 때까지 기다린 뒤 빈 목록을 돌려준다 */
    @Configuration(proxyBeanMethods = false)
    static class PublisherConfig {

        @Bean
        @SuppressWarnings("unchecked")
        OutboxPublisher outboxPublisher() {
            OutboxJpaRepository repository = mock(OutboxJpaRepository.class);
            when(repository.findByPublishedAtIsNullOrderByCreatedAtAsc(any())).thenAnswer(invocation -> {
                runs.incrementAndGet();
                started.countDown();
                release.await(10, TimeUnit.SECONDS);
                return List.of();
            });
            return new OutboxPublisher(repository, new OutboxMessageFactory(new ObjectMapper()), mock(KafkaTemplate.class),
                    new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    new OutboxPublisherProperties(100, 5, Duration.ofSeconds(5)));
        }
    }
}
