package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 스프링 빈인 응답 시간 초과 스케줄러, 반환 재요청 스케줄러에 스케줄러 락(ShedLock)이 걸리는지 확인함 (#184). Docker 필요.
 * 다른 스케줄러 테스트는 {@code new}로 만들어 락이 걸리지 않으므로, 빈이 ShedLock 프록시로 감싸지지 않아도(클래스, 메서드 구조가 바뀌는 등) 알 수 없음.
 * <p>
 * 스케줄러를 켜야 빈이 만들어지고, 켜면 기동하자마자 한 번 실행되며 shedlock 표를 씀. 그래서 schema.sql이 shedlock 표를 만드는 PostgreSQL로 띄움.
 * 처리 대상 청약이 없어 첫 실행에서 아무것도 바꾸지 않고, 주기를 길게 두어 테스트 중에 다시 돌지 않게 함.
 * 스케줄링이 켜지면 Outbox 발행기의 {@code @Scheduled}도 도므로(테스트 설정에서 꺼 둔 것은 발행기의 스케줄링 설정뿐) 발행 주기도 길게 둠.
 * <p>
 * 락이 걸려 있는지(프록시, 이름, 설정, shedlock 표)와 함께, 락이 두 번째 실행을 실제로 막는지도 확인함.
 * 락을 쥔 동안 같은 메서드를 다시 부르면 건너뛰므로(lockAtLeastFor, PROJECT_SETUP 5장), 막는 동작을 보는 테스트는 락이 풀린 것을 확인한 뒤
 * 연달아 부르고 저장소 조회 횟수를 센다. 그 외 테스트는 메서드를 직접 여러 번 부르지 않음.
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

    // 스케줄러가 실제로 실행됐는지를 저장소 조회 횟수로 셈 (두 스케줄러 모두 실행마다 대상 조회를 함)
    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

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

    @Test
    @DisplayName("응답 시간 초과 스케줄러는 락이 풀린 뒤 연달아 두 번 부르면 한 번만 실행하고, 락이 다시 풀리면 또 실행한다")
    void pendingTimeoutScheduler_skipsSecondRunWhileLockHeld() {
        // given: 기동 때의 첫 실행이 끝나 락이 풀리고, 이전 조회 기록을 비움
        awaitLockReleased(PENDING_TIMEOUT_LOCK);
        Mockito.clearInvocations(subscriptionRepository);

        // when: 락을 쥔 동안 같은 메서드를 다시 부름 (다른 인스턴스의 같은 시각 실행을 흉내 냄)
        pendingTimeoutScheduler.handleTimedOutPending();
        pendingTimeoutScheduler.handleTimedOutPending();

        // then: 두 번째는 건너뛰어 대상 조회는 한 번
        verify(subscriptionRepository, times(1)).findStalePendingIds(any(), anyInt());

        // when: 락이 다시 풀린 뒤 부름
        awaitLockReleased(PENDING_TIMEOUT_LOCK);
        pendingTimeoutScheduler.handleTimedOutPending();

        // then: 다시 실행되어 대상 조회는 두 번 (락이 영영 막지 않음)
        verify(subscriptionRepository, times(2)).findStalePendingIds(any(), anyInt());
    }

    @Test
    @DisplayName("반환 재요청 스케줄러는 락이 풀린 뒤 연달아 두 번 부르면 한 번만 실행하고, 락이 다시 풀리면 또 실행한다")
    void voidRetryScheduler_skipsSecondRunWhileLockHeld() {
        // given: 기동 때의 첫 실행이 끝나 락이 풀리고, 이전 조회 기록을 비움
        awaitLockReleased(VOID_RETRY_LOCK);
        Mockito.clearInvocations(subscriptionRepository);

        // when: 락을 쥔 동안 같은 메서드를 다시 부름
        voidRetryScheduler.retryStaleVoid();
        voidRetryScheduler.retryStaleVoid();

        // then: 두 번째는 건너뛰어 대상 조회는 한 번
        verify(subscriptionRepository, times(1)).findStaleVoidIds(any(), any(), anyInt());

        // when: 락이 다시 풀린 뒤 부름
        awaitLockReleased(VOID_RETRY_LOCK);
        voidRetryScheduler.retryStaleVoid();

        // then: 다시 실행되어 대상 조회는 두 번
        verify(subscriptionRepository, times(2)).findStaleVoidIds(any(), any(), anyInt());
    }

    @Test
    @DisplayName("두 스케줄러의 락은 서로 독립이라 한쪽이 락을 쥐고 있어도 다른 쪽은 실행된다")
    void schedulers_doNotBlockEachOther() {
        // given: 두 락이 모두 풀린 상태
        awaitLockReleased(PENDING_TIMEOUT_LOCK);
        awaitLockReleased(VOID_RETRY_LOCK);
        Mockito.clearInvocations(subscriptionRepository);

        // when: 응답 시간 초과 락을 쥔 상태에서 반환 재요청을 실행
        pendingTimeoutScheduler.handleTimedOutPending();
        voidRetryScheduler.retryStaleVoid();

        // then: 서로 다른 락 이름이라 둘 다 실행됨
        verify(subscriptionRepository, times(1)).findStalePendingIds(any(), anyInt());
        verify(subscriptionRepository, times(1)).findStaleVoidIds(any(), any(), anyInt());
    }

    // 락이 풀릴 때까지 기다림. 락 행은 기동 때의 첫 실행(별도 스레드)이 만들므로 행이 생기기 전의 조회 실패는 무시하고 다시 시도함.
    // 락 만료 시각(lock_until)은 시간대 없는 UTC라 DB 시각도 같은 기준으로 비교함 (schema.sql의 shedlock 표 설명 참고)
    private void awaitLockReleased(String lockName) {
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions().untilAsserted(() -> {
            Boolean released = jdbcTemplate.queryForObject(
                    "SELECT lock_until <= timezone('utc', now()) FROM shedlock WHERE name = ?", Boolean.class, lockName);
            assertThat(released).isTrue();
        });
    }

    private void assertProxiedByShedLock(Object scheduler) {
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
        assertThat(Arrays.stream(((Advised) scheduler).getAdvisors()).map(a -> a.getAdvice().getClass().getName()))
                .anyMatch(name -> name.startsWith("net.javacrumbs.shedlock"));
    }
}
