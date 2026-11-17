package com.gongmoju.subscription.infrastructure.scheduling;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 스프링 빈인 응답 시간 초과 스케줄러, 반환 재요청 스케줄러에 스케줄러 락(ShedLock)이 걸리는지 확인함 (#184). Docker 필요.
 * 다른 스케줄러 테스트는 {@code new}로 만들어 락이 걸리지 않으므로, 빈이 ShedLock 프록시로 감싸지지 않아도(클래스, 메서드 구조가 바뀌는 등) 알 수 없음.
 * <p>
 * 스케줄러를 켜야 빈이 만들어지고, 켜면 기동하자마자 한 번 실행되며 shedlock 표를 씀. 그래서 schema.sql이 shedlock 표를 만드는 PostgreSQL로 띄움.
 * 처리 대상 청약이 없어 첫 실행에서 아무것도 바꾸지 않고, 주기를 길게 두어 테스트 중에 다시 돌지 않게 함.
 * 스케줄링이 켜지면 Outbox 발행기의 {@code @Scheduled}도 도므로(테스트 설정에서 꺼 둔 것은 발행기의 스케줄링 설정뿐) 발행 주기도 길게 둠.
 * 메서드를 직접 여러 번 부르지 않음 (lockAtLeastFor 때문에 두 번째 호출부터 건너뛸 수 있음, PROJECT_SETUP 5장).
 */
@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "saga.timeout.enabled=true",
        "saga.timeout.interval=PT1H",
        "outbox.publisher.interval=PT1H"
})
@DisplayName("응답 시간 초과, 반환 재요청 스케줄러 락")
class SagaTimeoutSchedulerLockTest {

    private static final String PENDING_TIMEOUT_LOCK = "PendingTimeoutScheduler.handleTimedOutPending";
    private static final String VOID_RETRY_LOCK = "VoidRetryScheduler.retryStaleVoid";
    private static final String LOCK_AT_MOST_FOR = "${saga.timeout.lock-at-most-for:PT1M}";
    private static final String LOCK_AT_LEAST_FOR = "${saga.timeout.lock-at-least-for:PT4S}";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private PendingTimeoutScheduler pendingTimeoutScheduler;

    @Autowired
    private VoidRetryScheduler voidRetryScheduler;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("응답 시간 초과 스케줄러 빈은 ShedLock 프록시로 감싸져 handleTimedOutPending 실행에 락이 걸린다")
    void pendingTimeoutScheduler_isProxiedBySchedulerLock() throws Exception {
        // given, when: 실제 대상 클래스의 인자 없는 메서드에 붙은 값
        SchedulerLock lock = AopUtils.getTargetClass(pendingTimeoutScheduler).getDeclaredMethod("handleTimedOutPending")
                .getAnnotation(SchedulerLock.class);

        // then: 프록시이고, ShedLock의 메서드 가로채기가 들어 있음
        assertProxiedByShedLock(pendingTimeoutScheduler);
        assertThat(lock.name()).isEqualTo(PENDING_TIMEOUT_LOCK);
        assertThat(lock.lockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
        assertThat(lock.lockAtLeastFor()).isEqualTo(LOCK_AT_LEAST_FOR);
    }

    @Test
    @DisplayName("반환 재요청 스케줄러 빈은 ShedLock 프록시로 감싸져 retryStaleVoid 실행에 락이 걸린다")
    void voidRetryScheduler_isProxiedBySchedulerLock() throws Exception {
        // given, when: 실제 대상 클래스의 인자 없는 메서드에 붙은 값
        SchedulerLock lock = AopUtils.getTargetClass(voidRetryScheduler).getDeclaredMethod("retryStaleVoid")
                .getAnnotation(SchedulerLock.class);

        // then: 프록시이고, ShedLock의 메서드 가로채기가 들어 있음
        assertProxiedByShedLock(voidRetryScheduler);
        assertThat(lock.name()).isEqualTo(VOID_RETRY_LOCK);
        assertThat(lock.lockAtMostFor()).isEqualTo(LOCK_AT_MOST_FOR);
        assertThat(lock.lockAtLeastFor()).isEqualTo(LOCK_AT_LEAST_FOR);
    }

    @Test
    @DisplayName("기동 뒤 첫 실행에서 두 스케줄러가 각자 이름으로 락을 잡아 shedlock 표에 한 줄씩 남는다")
    void schedulers_acquireTheirOwnLocks_onFirstRun() {
        // given, when: 스케줄러는 기동하자마자 한 번 실행됨 (별도 스레드라 기다림)
        // then: 락 이름이 서로 달라 한쪽이 다른 쪽 실행을 막지 않음
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<String> names = jdbcTemplate.queryForList("SELECT name FROM shedlock", String.class);
            assertThat(names).contains(PENDING_TIMEOUT_LOCK, VOID_RETRY_LOCK);
        });
    }

    @Test
    @DisplayName("설정한 락 최대 시간은 최소 시간보다 짧지 않다 (짧으면 ShedLock이 락 설정을 만들 때 예외를 던져 스케줄러가 실행되지 않음)")
    void lockAtMostFor_isNotShorterThanLockAtLeastFor() {
        // when: 어노테이션과 같은 키, 같은 기본값으로 읽은 실제 설정값
        Duration atMost = Duration.parse(environment.resolveRequiredPlaceholders(LOCK_AT_MOST_FOR));
        Duration atLeast = Duration.parse(environment.resolveRequiredPlaceholders(LOCK_AT_LEAST_FOR));

        // then
        assertThat(atMost).isGreaterThanOrEqualTo(atLeast);
    }

    private void assertProxiedByShedLock(Object scheduler) {
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
        assertThat(Arrays.stream(((Advised) scheduler).getAdvisors()).map(a -> a.getAdvice().getClass().getName()))
                .anyMatch(name -> name.startsWith("net.javacrumbs.shedlock"));
    }
}
