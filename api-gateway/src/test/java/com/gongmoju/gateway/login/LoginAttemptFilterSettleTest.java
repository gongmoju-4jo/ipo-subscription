package com.gongmoju.gateway.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.gateway.login.LoginAttemptRedisRepository.Reservation;
import com.gongmoju.gateway.support.JsonBodyReader;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 응답을 보낸 뒤 확정(Redis) 중에 클라이언트가 끊는 경우. 확정이 함께 취소되면 5번째 실패의 15분 차단이 걸리지 않는다
class LoginAttemptFilterSettleTest {

    @Test
    void 확정_중에_요청이_취소돼도_확정은_끝까지_한_번만_실행된다() throws Exception {
        AtomicInteger recorded = new AtomicInteger();
        LoginAttemptRedisRepository repository = mock(LoginAttemptRedisRepository.class);
        when(repository.reserve(anyString())).thenReturn(Mono.just(new Reservation(true, Duration.ZERO)));
        // 실패 기록이 300ms 걸린다. 그 사이(100ms)에 취소한다
        when(repository.recordFailure(anyString())).thenReturn(
                Mono.delay(Duration.ofMillis(300)).then(Mono.fromRunnable(recorded::incrementAndGet)));
        when(repository.release(anyString())).thenReturn(Mono.empty());

        ObjectMapper objectMapper = new ObjectMapper();
        LoginAttemptFilter filter = new LoginAttemptFilter(repository,
                new LoginAttemptProperties(5, Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new JsonBodyReader(objectMapper), objectMapper);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"loginId\":\"investor01\",\"password\":\"wrong\"}"));
        // 서비스가 401을 준 것처럼 응답을 끝낸다
        GatewayFilterChain chain = ex -> {
            ex.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return ex.getResponse().setComplete();
        };

        Disposable request = filter.filter(exchange, chain).subscribe();
        Thread.sleep(100);
        request.dispose();
        Thread.sleep(600);

        assertThat(recorded.get()).isEqualTo(1);
        verify(repository, times(1)).recordFailure("investor01");
        verify(repository, never()).release(anyString());
    }
}
