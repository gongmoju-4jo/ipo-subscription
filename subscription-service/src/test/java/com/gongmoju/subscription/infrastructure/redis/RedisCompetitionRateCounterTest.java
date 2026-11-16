package com.gongmoju.subscription.infrastructure.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis 장애 때의 동작 (예외를 던지지 않고, 실패하면 retryAfter 동안 Redis를 부르지 않으며, 놓친 더하기는 돌아온 뒤 사본을 지움).
 * 실제 Redis 동작은 CompetitionRateRedisIntegrationTest.
 */
@DisplayName("경쟁률 사본 Redis 장애 처리")
class RedisCompetitionRateCounterTest {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(10);
    private static final UUID IPO = UUID.randomUUID();

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T00:00:00Z"));
    private RedisCompetitionRateCounter counter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        counter = new RedisCompetitionRateCounter(redis,
                new CompetitionRateProperties(true, Duration.ofMinutes(5), Duration.ofSeconds(30), RETRY_AFTER), clock);
    }

    @Test
    @DisplayName("읽기가 실패하면 예외 없이 빈 결과를 주고, retryAfter 동안 읽기, 더하기, 채우기 시작과 끝 모두 Redis를 부르지 않는다")
    void failure_returnsEmpty_andSkipsRedisUntilRetryAfter() {
        // given
        when(values.multiGet(anyList())).thenThrow(new RedisConnectionFailureException("down"));

        // when: 실패
        assertThat(counter.find(List.of(IPO))).isEmpty();
        // 건너뛰는 동안
        clock.advance(RETRY_AFTER.minusSeconds(1));
        assertThat(counter.find(List.of(IPO))).isEmpty();
        counter.addConfirmed(IPO, 10);
        assertThat(counter.startLoading(List.of(IPO))).isEmpty();
        counter.finishLoading(Map.of(IPO, 10L));

        // then: 처음 한 번만 불렀음
        verify(values, times(1)).multiGet(anyList());
        verify(redis, never()).execute(any(), anyList(), any(Object[].class));
        verify(redis, never()).delete(anyList());
    }

    @Test
    @DisplayName("retryAfter가 지나면 다시 Redis를 부른다")
    void failure_retriesRedis_afterRetryAfter() {
        // given
        when(values.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(List.of("123"));
        counter.find(List.of(IPO));

        // when
        clock.advance(RETRY_AFTER);

        // then
        assertThat(counter.find(List.of(IPO))).containsEntry(IPO, 123L);
        verify(values, times(2)).multiGet(anyList());
    }

    @Test
    @DisplayName("더하기를 건너뛴 공모주는 Redis가 돌아오면 사본을 먼저 지운다 (낮은 값이 남지 않고 다음 조회가 DB로 다시 채우게)")
    void skippedAdds_deleteCounterWhenRedisIsBack() {
        // given: 읽기 실패로 건너뛰는 중에 확정이 들어옴
        when(values.multiGet(anyList()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(Arrays.asList((String) null));
        counter.find(List.of(IPO));
        counter.addConfirmed(IPO, 10);

        // when: retryAfter가 지나 다시 부름
        clock.advance(RETRY_AFTER);
        counter.find(List.of(IPO));

        // then
        verify(redis).delete(List.of(RedisCompetitionRateCounter.key(IPO)));
    }

    @Test
    @DisplayName("더하기, 채우기 시작과 끝이 실패해도 예외를 던지지 않는다 (청약 확정과 조회가 실패하지 않게)")
    void writeFailures_doNotThrow() {
        when(redis.execute(any(), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyList())).thenReturn(1L);

        assertThatCode(() -> counter.addConfirmed(IPO, 10)).doesNotThrowAnyException();
        clock.advance(RETRY_AFTER);
        assertThatCode(() -> assertThat(counter.startLoading(List.of(IPO))).isEmpty()).doesNotThrowAnyException();
        clock.advance(RETRY_AFTER);
        assertThatCode(() -> counter.finishLoading(Map.of(IPO, 10L))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 없는 공모주와 정수가 아닌 값은 결과에 넣지 않는다 (DB로 계산하게)")
    void find_skipsMissingAndInvalidValues() {
        UUID other = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("5", null, "x"));

        assertThat(counter.find(List.of(IPO, other, broken))).containsExactly(Map.entry(IPO, 5L));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
