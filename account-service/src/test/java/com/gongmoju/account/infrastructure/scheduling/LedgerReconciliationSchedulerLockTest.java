package com.gongmoju.account.infrastructure.scheduling;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 스프링 빈인 원장 대사 스케줄러에 스케줄러 락(ShedLock)이 적용되는지 확인한다 (#167, IpoCloseSchedulerLockTest와 같은 방식).
// 기존 LedgerReconciliationSchedulerTest는 new로 생성해 락이 적용되지 않으므로, 빈이 ShedLock 프록시로 감싸지지 않아도 알 수 없다.
// 테스트 설정은 대사 스케줄을 비활성화해서(ledger.reconciliation.enabled=false) 이 테스트만 활성화해 빈을 생성한다.
// cron(기본 매일 04:00)이라 테스트 실행 중 대사가 실행되지 않는다. shedlock 표는 schema.sql이 생성해서 PostgreSQL 컨테이너로 실행한다.
// 메서드를 직접 여러 번 호출하지 않는다 (lockAtLeastFor 때문에 두 번째 호출부터 건너뛸 수 있음, PROJECT_SETUP 5장).
@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ledger.reconciliation.enabled=true",
        // 측정용 설정처럼 환경변수로 락 최소 유지 시간을 변경 (application.yml의 ${LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR:PT5M})
        "LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR=PT5S"
})
@DisplayName("원장 대사 스케줄러 락")
class LedgerReconciliationSchedulerLockTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

    @Autowired
    private LedgerReconciliationScheduler scheduler;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("원장 대사 스케줄러 빈은 ShedLock 프록시로 감싸져 reconcile 실행에 락이 적용된다")
    void reconciliationScheduler_isProxiedBySchedulerLock() throws Exception {
        // then: 프록시이고, ShedLock의 메서드 가로채기가 포함됨
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
        assertThat(Arrays.stream(((Advised) scheduler).getAdvisors()).map(a -> a.getAdvice().getClass().getName()))
                .anyMatch(name -> name.startsWith("net.javacrumbs.shedlock"));
        // 락 이름과 시간 (실제 대상 클래스의 메서드에 붙은 값)
        SchedulerLock lock = AopUtils.getTargetClass(scheduler).getDeclaredMethod("reconcile").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("LedgerReconciliationScheduler.reconcile");
        assertThat(lock.lockAtMostFor()).isEqualTo("${ledger.reconciliation.lock-at-most-for:PT30M}");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${ledger.reconciliation.lock-at-least-for:PT5M}");
    }

    @Test
    @DisplayName("락 최소 유지 시간은 환경변수 LEDGER_RECONCILIATION_LOCK_AT_LEAST_FOR로 변경되고, 최대 시간은 지정하지 않으면 기본값 PT30M이다")
    void lockTimes_canBeOverriddenByEnvironmentVariable() {
        // when: 어노테이션과 같은 키로 읽은 실제 설정값
        Duration atLeast = Duration.parse(environment.resolveRequiredPlaceholders("${ledger.reconciliation.lock-at-least-for:PT5M}"));
        Duration atMost = Duration.parse(environment.resolveRequiredPlaceholders("${ledger.reconciliation.lock-at-most-for:PT30M}"));

        // then
        assertThat(atLeast).isEqualTo(Duration.ofSeconds(5));
        assertThat(atMost).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("설정한 락 최대 시간은 최소 시간을 초과한다 (이하면 ShedLock이 락 설정을 생성할 때 예외를 발생시켜 대사가 실행되지 않음)")
    void lockAtMostFor_isLongerThanLockAtLeastFor() {
        // when: 어노테이션과 같은 키, 같은 기본값으로 읽은 실제 설정값
        Duration atMost = Duration.parse(environment.resolveRequiredPlaceholders("${ledger.reconciliation.lock-at-most-for:PT30M}"));
        Duration atLeast = Duration.parse(environment.resolveRequiredPlaceholders("${ledger.reconciliation.lock-at-least-for:PT5M}"));

        // then
        assertThat(atMost).isGreaterThan(atLeast);
    }
}
