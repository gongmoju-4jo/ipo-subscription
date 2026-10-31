package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.common.feign.FeignClientException;
import com.gongmoju.common.feign.FeignServerException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// application.yml의 Circuit Breaker 설정이 실제로 적용되는지 확인한다. 청약 서비스 대신 가짜 Feign 클라이언트를 쓴다
// schema.sql은 PostgreSQL 문법(부분 인덱스)이라 H2에서는 실행하지 않는다
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:subscription-client-cb;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
class SubscriptionClientCircuitBreakerTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");

    @MockitoBean
    private SubscriptionClient client;

    @Autowired
    private SubscriptionClientAdapter adapter;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("subscription-service");
        circuitBreaker.reset();
    }

    // application.yml 값이 적용된다 (최근 10회, 최소 5회, 실패 50%, 30초 뒤 다시 시도, 시험 호출 3회)
    @Test
    void appliesConfiguredValues() {
        CircuitBreakerConfig config = circuitBreaker.getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(Duration.ofSeconds(30).toMillis());
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
    }

    // 5xx가 최소 호출 수(5회)만큼 쌓이면 열리고, 열린 뒤에는 청약 서비스를 호출하지 않고 바로 실패한다
    @Test
    void opensAfterServerFailuresAndRejectsCallsWithoutCallingSubscriptionService() {
        when(client.getConfirmedSubscriptions(any(), anyString(), anyInt(), anyInt()))
                .thenThrow(new FeignServerException(503, "SERVICE_UNAVAILABLE", "청약 서비스 오류", "getConfirmedSubscriptions"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> adapter.readAll(IPO_ID)).isInstanceOf(FeignServerException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        clearInvocations(client);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID)).isInstanceOf(CallNotPermittedException.class);
        verify(client, never()).getConfirmedSubscriptions(any(), anyString(), anyInt(), anyInt());
    }

    // 4xx(아직 CLOSED 아님 등)는 청약 서비스가 정상 응답한 것이라 실패로 세지 않는다
    @Test
    void doesNotCountClientErrorsAsFailures() {
        when(client.getConfirmedSubscriptions(any(), anyString(), anyInt(), anyInt()))
                .thenThrow(new FeignClientException(409, "INVALID_STATUS_TRANSITION", "공모주가 아직 마감되지 않았습니다.", List.of()));
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> adapter.readAll(IPO_ID)).isInstanceOf(FeignClientException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }
}
