package com.gongmoju.common.schedulerlock;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러 락 자동 설정이 인스턴스 여러 개에서 같은 작업을 한 번만 실행하게 하는지 실제 PostgreSQL로 확인함. Docker 필요.
 * <p>
 * 인스턴스 2개는 같은 DB를 쓰는 애플리케이션 컨텍스트 2개로 흉내 낸다 (각자 락 저장소와 커넥션 풀을 가짐).
 * 작업 메서드는 실제 스케줄러(예: {@code IpoCloseScheduler.closeDueIpos()})처럼 public이 아닌 메서드로 둔다.
 */
@Testcontainers
@DisplayName("스케줄러 락 자동 설정 (PostgreSQL)")
class CommonSchedulerLockAutoConfigurationTest {

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

    /** 두 컨텍스트(인스턴스)가 함께 세는 실행 횟수. 테스트마다 초기화 */
    static final AtomicInteger executions = new AtomicInteger();
    /** 실행 중인 작업을 붙잡아 두어 두 인스턴스의 실행 시점을 겹치게 한다 */
    static volatile CountDownLatch release = new CountDownLatch(0);
    static volatile CountDownLatch started = new CountDownLatch(1);

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, AopAutoConfiguration.class, CommonSchedulerLockAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword());
    }

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute(CREATE_SHEDLOCK_TABLE);
        jdbc.update("DELETE FROM shedlock");
        executions.set(0);
        release = new CountDownLatch(0);
        started = new CountDownLatch(1);
    }

    @Test
    @DisplayName("인스턴스 2개가 같은 작업을 동시에 실행하면 한 인스턴스만 실행한다")
    void sameTaskOnTwoInstances_runsOnce() {
        runner().withUserConfiguration(LockedTaskConfig.class).run(instanceA ->
                runner().withUserConfiguration(LockedTaskConfig.class).run(instanceB -> {
                    // given: A가 작업을 시작해 붙잡혀 있는 동안
                    release = new CountDownLatch(1);
                    ExecutorService pool = Executors.newSingleThreadExecutor();
                    Future<?> runningOnA = pool.submit(() -> instanceA.getBean(LockedTask.class).run());
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

                    // when: B가 같은 작업을 실행하면
                    instanceB.getBean(LockedTask.class).run();
                    release.countDown();
                    runningOnA.get(10, TimeUnit.SECONDS);
                    pool.shutdown();

                    // then: B는 락을 얻지 못해 건너뛰고, 실행은 A의 한 번뿐이다
                    assertThat(executions.get()).isEqualTo(1);
                }));
    }

    @Test
    @DisplayName("앞 실행이 끝나면 락이 풀려 다른 인스턴스가 다음 실행을 할 수 있다")
    void afterFirstRunFinishes_otherInstanceCanRun() {
        runner().withUserConfiguration(LockedTaskConfig.class).run(instanceA ->
                runner().withUserConfiguration(LockedTaskConfig.class).run(instanceB -> {
                    // given: A가 작업을 끝낸 뒤 (lockAtLeastFor 0이라 끝나자마자 락이 풀림)
                    instanceA.getBean(LockedTask.class).run();

                    // when: B가 같은 작업을 실행하면
                    instanceB.getBean(LockedTask.class).run();

                    // then: B도 실행한다 (락은 동시 실행만 막고, 정해진 주기의 실행은 막지 않음)
                    assertThat(executions.get()).isEqualTo(2);
                }));
    }

    @Test
    @DisplayName("lockAtLeastFor 동안은 앞 실행이 빨리 끝나도 다른 인스턴스가 다시 실행하지 않는다")
    void withinLockAtLeastFor_otherInstanceSkips() {
        runner().withUserConfiguration(MinimumLockTaskConfig.class).run(instanceA ->
                runner().withUserConfiguration(MinimumLockTaskConfig.class).run(instanceB -> {
                    // given: A가 작업을 금방 끝냈지만 lockAtLeastFor(30초)가 남아 있을 때
                    instanceA.getBean(MinimumLockTask.class).run();

                    // when: B가 같은 작업을 실행하면
                    instanceB.getBean(MinimumLockTask.class).run();

                    // then: B는 건너뛴다 (시계가 조금 다른 두 인스턴스가 같은 주기에 연달아 실행하는 것을 막음)
                    assertThat(executions.get()).isEqualTo(1);
                }));
    }

    @Test
    @DisplayName("@Scheduled로 실행되는 public이 아닌 메서드에도 락이 걸린다")
    void scheduledPackagePrivateMethod_isLocked() {
        runner().withUserConfiguration(ScheduledTaskConfig.class).run(instance -> {
            // given: 50ms마다 실행되고 lockAtLeastFor가 30초인 스케줄러
            // when: 1초 동안 두면 (락이 없으면 약 20번 실행)
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (executions.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(1000);

            // then: 첫 실행이 잡은 락 때문에 한 번만 실행되고, 락 줄이 남는다
            assertThat(executions.get()).isEqualTo(1);
            assertThat(lockRowCount(instance, "test-scheduled-task")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("DB가 없는 앱에는 락 저장소를 등록하지 않는다")
    void withoutDataSource_registersNothing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonSchedulerLockAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(LockProvider.class));
    }

    private static Integer lockRowCount(ConfigurableApplicationContext context, String name) {
        return context.getBean(JdbcTemplate.class)
                .queryForObject("SELECT count(*) FROM shedlock WHERE name = ?", Integer.class, name);
    }

    /** 실행되면 횟수를 세고, release가 열릴 때까지 기다리는 작업 (lockAtLeastFor 0) */
    static class LockedTask {
        @SchedulerLock(name = "test-task", lockAtMostFor = "PT30S")
        void run() {
            executions.incrementAndGet();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 곧바로 끝나지만 30초 동안 락을 유지하는 작업 */
    static class MinimumLockTask {
        @SchedulerLock(name = "test-minimum-lock-task", lockAtMostFor = "PT1M", lockAtLeastFor = "PT30S")
        void run() {
            executions.incrementAndGet();
        }
    }

    /** 실제 스케줄러처럼 @Scheduled로 실행되는 작업 */
    static class ScheduledTask {
        @Scheduled(fixedDelay = 50)
        @SchedulerLock(name = "test-scheduled-task", lockAtMostFor = "PT1M", lockAtLeastFor = "PT30S")
        void run() {
            executions.incrementAndGet();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LockedTaskConfig {
        @Bean
        LockedTask lockedTask() {
            return new LockedTask();
        }

        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MinimumLockTaskConfig {
        @Bean
        MinimumLockTask minimumLockTask() {
            return new MinimumLockTask();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class ScheduledTaskConfig {
        @Bean
        ScheduledTask scheduledTask() {
            return new ScheduledTask();
        }

        @Bean
        JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
