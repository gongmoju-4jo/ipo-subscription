package com.gongmoju.gateway.queue;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import reactor.core.Exceptions;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import static com.gongmoju.gateway.queue.AdmissionFailureReason.OTHER;
import static com.gongmoju.gateway.queue.AdmissionFailureReason.REDIS_CONNECTION;
import static com.gongmoju.gateway.queue.AdmissionFailureReason.REDIS_ERROR;
import static com.gongmoju.gateway.queue.AdmissionFailureReason.REDIS_OOM;
import static com.gongmoju.gateway.queue.AdmissionFailureReason.REDIS_TIMEOUT;
import static com.gongmoju.gateway.queue.AdmissionFailureReason.classify;
import static org.assertj.core.api.Assertions.assertThat;

// 예외 → 실패 사유 분류. 실제 Redis로 만든 예외는 AdmissionSchedulerTest가 확인한다
class AdmissionFailureReasonTest {

    @Test
    void 메모리_상한_거부는_감싸여_있어도_redis_oom이다() {
        RedisCommandExecutionException oom =
                new RedisCommandExecutionException("OOM command not allowed when used memory > 'maxmemory'.");
        assertThat(classify(oom)).isEqualTo(REDIS_OOM);
        assertThat(classify(new RedisSystemException("Error in execution", oom))).isEqualTo(REDIS_OOM);
    }

    @Test
    void 응답_시간_초과는_redis_timeout이다() {
        assertThat(classify(new RedisCommandTimeoutException("Command timed out after 2 second(s)"))).isEqualTo(REDIS_TIMEOUT);
        assertThat(classify(new QueryTimeoutException("Redis command timed out"))).isEqualTo(REDIS_TIMEOUT);
        // 스케줄러의 timeout 연산자가 낸 시간 초과를 block()이 감싼 모양
        assertThat(classify(Exceptions.propagate(new TimeoutException("Did not observe any item")))).isEqualTo(REDIS_TIMEOUT);
    }

    @Test
    void 연결을_맺지_못하면_redis_connection이다() {
        assertThat(classify(new RedisConnectionFailureException("Unable to connect to Redis",
                new RedisConnectionException("Unable to connect to redis:6379", new ConnectException("Connection refused")))))
                .isEqualTo(REDIS_CONNECTION);
        assertThat(classify(new RuntimeException(new UnknownHostException("redis")))).isEqualTo(REDIS_CONNECTION);
    }

    @Test
    void 그_밖의_Redis_오류는_redis_error다() {
        assertThat(classify(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("WRONGTYPE Operation against a key holding the wrong kind of value"))))
                .isEqualTo(REDIS_ERROR);
        assertThat(classify(new RedisCommandExecutionException("ERR Error running script"))).isEqualTo(REDIS_ERROR);
    }

    @Test
    void Redis와_무관한_오류는_other다() {
        assertThat(classify(new IllegalStateException("버그"))).isEqualTo(OTHER);
        assertThat(classify(new NullPointerException())).isEqualTo(OTHER);
    }

    @Test
    void 원인이_순환해도_멈춘다() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(classify(a)).isEqualTo(OTHER);
    }

    @Test
    void 사유_라벨은_알림_규칙의_값과_같다() {
        // docker/grafana/provisioning/alerting/rules.yml의 QueueAdmissionDown 문구가 이 값으로 갈린다
        assertThat(AdmissionFailureReason.values()).extracting(AdmissionFailureReason::tag)
                .containsExactly("redis_oom", "redis_timeout", "redis_connection", "redis_error", "other");
    }
}
