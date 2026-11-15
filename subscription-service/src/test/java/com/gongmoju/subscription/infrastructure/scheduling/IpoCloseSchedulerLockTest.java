package com.gongmoju.subscription.infrastructure.scheduling;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 스프링 빈인 마감 스케줄러에 스케줄러 락(ShedLock)이 걸리는지 확인함 (#149).
 * 다른 스케줄러 테스트는 {@code new}로 만들어 락이 걸리지 않으므로, 빈이 ShedLock 프록시로 감싸지지 않아도(클래스, 메서드 구조가 바뀌는 등) 알 수 없음.
 * <p>
 * 스케줄러를 켜야 빈이 만들어지고, 켜면 기동하자마자 한 번 실행되며 shedlock 표를 쓴다. 그래서 schema.sql이 shedlock 표를 만드는 PostgreSQL로 띄운다.
 * 마감 유예를 아주 길게 두어 첫 실행에서 아무것도 마감하지 않게 하고(시드의 마감 시각 지난 공모주도 그대로), 주기를 길게 두어 테스트 중에 다시 돌지 않게 한다.
 * 마감 스케줄러를 켜면 스케줄링이 켜져 Outbox 발행기의 @Scheduled도 돈다 (테스트 설정에서 꺼 둔 것은 발행기의 스케줄링 설정뿐). 마감이 없으니 발행할 것도 없고, 주기도 길게 둔다.
 * 메서드를 직접 여러 번 부르지 않는다 (lockAtLeastFor 때문에 두 번째 호출부터 건너뛸 수 있음, PROJECT_SETUP 5장).
 */
@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ipo.close.enabled=true",
        "ipo.close.interval=PT1H",
        "ipo.close.grace-period=P36500D",
        "outbox.publisher.interval=PT1H"
})
@DisplayName("마감 스케줄러 락")
class IpoCloseSchedulerLockTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private IpoCloseScheduler scheduler;

    @Test
    @DisplayName("마감 스케줄러 빈은 ShedLock 프록시로 감싸져 closeDueIpos 실행에 락이 걸린다")
    void closeScheduler_isProxiedBySchedulerLock() throws Exception {
        // then: 프록시이고, ShedLock의 메서드 가로채기가 들어 있음
        assertThat(AopUtils.isAopProxy(scheduler)).isTrue();
        assertThat(Arrays.stream(((Advised) scheduler).getAdvisors()).map(a -> a.getAdvice().getClass().getName()))
                .anyMatch(name -> name.startsWith("net.javacrumbs.shedlock"));
        // 락 이름과 시간 (실제 대상 클래스의 메서드에 붙은 값)
        SchedulerLock lock = AopUtils.getTargetClass(scheduler).getDeclaredMethod("closeDueIpos").getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("IpoCloseScheduler.closeDueIpos");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT1M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("${ipo.close.lock-at-least-for:PT4S}");
    }
}
