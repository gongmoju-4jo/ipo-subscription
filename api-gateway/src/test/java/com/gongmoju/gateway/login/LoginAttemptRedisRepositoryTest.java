package com.gongmoju.gateway.login;

import com.gongmoju.gateway.login.LoginAttemptRedisRepository.Reservation;
import com.gongmoju.gateway.queue.RedisTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 로그인 시도 제한 Lua 스크립트를 실제 Redis(redis:7)로 확인한다
class LoginAttemptRedisRepositoryTest extends RedisTestSupport {

    private static final Duration BLOCK = Duration.ofMinutes(15);
    private static final String LOGIN_ID = "investor01";

    private final LoginAttemptRedisRepository repository = new LoginAttemptRedisRepository(TEMPLATE,
            new LoginAttemptProperties(5, BLOCK, Duration.ofMillis(200), Duration.ofSeconds(5)));

    @BeforeEach
    void setUp() {
        flushAll();
    }

    @Test
    void 실패가_없으면_통과하고_횟수를_1_올리며_만료는_차단_시간이다() {
        assertThat(reserve().allowed()).isTrue();

        assertThat(count()).isEqualTo(1);
        assertThat(ttl()).isBetween(BLOCK.minusSeconds(5), BLOCK);
    }

    @Test
    void 네_번_실패해도_다섯_번째_시도는_통과한다() {
        fail(4);

        assertThat(reserve().allowed()).isTrue();
    }

    @Test
    void 다섯_번_실패하면_차단되고_남은_시간을_돌려준다() {
        fail(5);

        Reservation reservation = reserve();

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.retryAfter()).isBetween(BLOCK.minusSeconds(5), BLOCK);
    }

    @Test
    void 첫_실패_14분_뒤_다섯_번째로_실패해도_그때부터_15분_차단한다() {
        fail(4);
        // 첫 실패 뒤 14분이 지나 만료가 1분 남은 상태
        setTtl(Duration.ofMinutes(1));

        fail(1);

        assertThat(ttl()).isBetween(BLOCK.minusSeconds(5), BLOCK);
    }

    @Test
    void 네_번째_실패까지는_만료를_다시_맞추지_않는다() {
        fail(1);
        setTtl(Duration.ofMinutes(10));

        fail(3);

        assertThat(ttl()).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 차단_중_시도는_횟수와_차단_시간을_늘리지_않는다() {
        fail(5);
        setTtl(Duration.ofMinutes(3));

        Reservation reservation = reserve();

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.retryAfter()).isLessThanOrEqualTo(Duration.ofMinutes(3));
        assertThat(count()).isEqualTo(5);
        assertThat(ttl()).isLessThanOrEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void 성공하면_횟수가_지워져_다시_다섯_번_실패해야_차단된다() {
        fail(3);
        reserve();
        repository.clear(LOGIN_ID).block();

        assertThat(TEMPLATE.hasKey(LoginAttemptRedisRepository.key(LOGIN_ID)).block()).isFalse();
        fail(4);
        assertThat(reserve().allowed()).isTrue();
    }

    @Test
    void 실패로_세지_않는_응답이면_예약한_횟수를_되돌리고_만료는_유지한다() {
        fail(2);
        setTtl(Duration.ofMinutes(10));
        reserve();

        repository.release(LOGIN_ID).block();

        assertThat(count()).isEqualTo(2);
        assertThat(ttl()).isLessThanOrEqualTo(Duration.ofMinutes(10)).isGreaterThan(Duration.ofMinutes(9));
    }

    @Test
    void 다섯_번째_시도가_실패로_세지_않으면_차단되지_않는다() {
        fail(4);
        reserve();

        repository.release(LOGIN_ID).block();

        assertThat(reserve().allowed()).isTrue();
    }

    @Test
    void 키가_없을_때_되돌려도_음수가_되지_않는다() {
        repository.release(LOGIN_ID).block();

        assertThat(TEMPLATE.opsForValue().get(LoginAttemptRedisRepository.key(LOGIN_ID)).block()).isNull();
    }

    @Test
    void 만료_없는_키가_있어도_영구_차단되지_않는다() {
        TEMPLATE.opsForValue().set(LoginAttemptRedisRepository.key(LOGIN_ID), "5").block();

        Reservation reservation = reserve();

        assertThat(reservation.allowed()).isFalse();
        assertThat(reservation.retryAfter()).isEqualTo(BLOCK);
        assertThat(ttl()).isBetween(BLOCK.minusSeconds(5), BLOCK);
    }

    @Test
    void 로그인_ID마다_따로_센다() {
        fail(5);

        assertThat(repository.reserve("investor02").block().allowed()).isTrue();
    }

    @Test
    void 키에는_로그인_ID_원문이_없고_길이가_일정하다() {
        String shortKey = LoginAttemptRedisRepository.key("a");
        String longKey = LoginAttemptRedisRepository.key("x".repeat(10_000));

        assertThat(shortKey).startsWith("login-fail:").hasSize(longKey.length()).hasSize("login-fail:".length() + 64);
        assertThat(LoginAttemptRedisRepository.key(LOGIN_ID)).doesNotContain(LOGIN_ID);
        // 대소문자가 다르면 다른 ID다 (서비스도 로그인 ID를 그대로 비교한다)
        assertThat(LoginAttemptRedisRepository.key("Investor01")).isNotEqualTo(LoginAttemptRedisRepository.key(LOGIN_ID));
    }

    @Test
    void 동시에_20건을_예약해도_통과는_다섯_건뿐이다() throws Exception {
        int attempts = 20;
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                results.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return reserve().allowed();
                }, executor));
            }
            start.countDown();
            long allowed = results.stream().map(CompletableFuture::join).filter(Boolean::booleanValue).count();

            assertThat(allowed).isEqualTo(5);
            assertThat(count()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 설정값이_잘못되면_기동할_때_실패한다() {
        assertThatThrownBy(() -> new LoginAttemptProperties(0, BLOCK, Duration.ofMillis(200), Duration.ofSeconds(5)))
                .hasMessageContaining("login-attempt.max-failures");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, Duration.ZERO, Duration.ofMillis(200), Duration.ofSeconds(5)))
                .hasMessageContaining("login-attempt.block-duration");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, BLOCK, Duration.ZERO, Duration.ofSeconds(5)))
                .hasMessageContaining("login-attempt.redis-timeout");
        assertThatThrownBy(() -> new LoginAttemptProperties(5, BLOCK, Duration.ofMillis(200), Duration.ofSeconds(-1)))
                .hasMessageContaining("login-attempt.redis-skip");
    }

    private Reservation reserve() {
        return repository.reserve(LOGIN_ID).block();
    }

    // 예약 후 401을 받은 것과 같게 n번 실패시킨다
    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            assertThat(reserve().allowed()).isTrue();
            repository.recordFailure(LOGIN_ID).block();
        }
    }

    private long count() {
        return Long.parseLong(TEMPLATE.opsForValue().get(LoginAttemptRedisRepository.key(LOGIN_ID)).block());
    }

    private Duration ttl() {
        return TEMPLATE.getExpire(LoginAttemptRedisRepository.key(LOGIN_ID)).block();
    }

    private void setTtl(Duration ttl) {
        TEMPLATE.expire(LoginAttemptRedisRepository.key(LOGIN_ID), ttl).block();
    }
}
