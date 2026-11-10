package com.gongmoju.gateway.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionSchedulerTest extends RedisTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-09T07:00:00.000Z");
    private static final Instant ALIVE = NOW.plusSeconds(60);

    private final QueueRedisRepository repository = new QueueRedisRepository(TEMPLATE);

    @BeforeEach
    void setUp() {
        flushAll();
    }

    @Test
    void 입장_인원을_공모주_수로_나누고_나머지는_주기마다_돌아가며_준다() {
        assertThat(AdmissionScheduler.split(433, 2, 0)).containsExactly(217, 216);
        assertThat(AdmissionScheduler.split(433, 2, 1)).containsExactly(216, 217);
        assertThat(AdmissionScheduler.split(5, 3, 0)).containsExactly(2, 2, 1);
        assertThat(AdmissionScheduler.split(2, 3, 2)).containsExactly(1, 0, 1);
        assertThat(AdmissionScheduler.split(433, 1, 7)).containsExactly(433);
    }

    @Test
    void 입장_인원은_전체_공모주_합이다() {
        UUID ipoA = UUID.randomUUID();
        UUID ipoB = UUID.randomUUID();
        enterUsers(ipoA, 10);
        enterUsers(ipoB, 10);

        scheduler(5).admitOnce().block();

        // 공모주별로 5명씩이 아니라 둘을 합쳐 5명 (3명 + 2명)
        assertThat(waitingCount(ipoA) + waitingCount(ipoB)).isEqualTo(15);
        assertThat(waitingCount(ipoA)).isIn(7L, 8L);
    }

    @Test
    void 대기자가_없으면_아무것도_하지_않는다() {
        scheduler(433).admitOnce().block();

        assertThat(repository.activeIpoIds().collectList().block()).isEmpty();
    }

    @Test
    void 대기자가_입장_인원보다_적으면_모두_허가하고_대상에서_뺀다() {
        UUID ipoId = UUID.randomUUID();
        enterUsers(ipoId, 3);

        scheduler(433).admitOnce().block();

        assertThat(waitingCount(ipoId)).isZero();
        assertThat(repository.activeIpoIds().collectList().block()).isEmpty();
    }

    @Test
    void 입장_처리가_성공하면_지표가_1이고_첫_주기_전에는_0이다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(repository, registry, Duration.ofSeconds(5));
        assertThat(up(registry)).isZero();

        scheduler.run();

        assertThat(up(registry)).isEqualTo(1);
    }

    @Test
    void Redis에_접속할_수_없으면_지표가_0이다() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build());
        factory.afterPropertiesSet();
        factory.start();
        try {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            AdmissionScheduler scheduler = scheduler(new QueueRedisRepository(new ReactiveStringRedisTemplate(factory)),
                    registry, Duration.ofSeconds(5));

            scheduler.run();

            assertThat(up(registry)).isZero();
            assertThat(failingReasons(registry)).containsExactly("redis_connection");
        } finally {
            factory.destroy();
        }
    }

    @Test
    void Redis_메모리_상한에_닿으면_사유가_redis_oom이고_대기자는_줄에_그대로_남는다() {
        UUID ipoId = UUID.randomUUID();
        enterUsers(ipoId, 3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(repository, registry, Duration.ofSeconds(5));
        // 실제 OOM: 메모리 상한을 1바이트로 낮추면 noeviction이라 쓰기(SET)가 거부된다. 같은 컨테이너를 쓰는 다른 테스트를 위해 꼭 되돌린다
        setMaxMemory("1");
        try {
            scheduler.run();
        } finally {
            setMaxMemory("0");
        }

        assertThat(up(registry)).isZero();
        assertThat(failingReasons(registry)).containsExactly("redis_oom");
        // 허가를 쓰지 못한 사람은 줄에서 빠지지 않는다 (허가 없이 줄에서 사라지면 안 된다)
        assertThat(waitingCount(ipoId)).isEqualTo(3);

        scheduler.run();
        assertThat(waitingCount(ipoId)).isZero();
        assertThat(failingReasons(registry)).isEmpty();
    }

    @Test
    void 키_자료형이_맞지_않는_등_그_밖의_Redis_오류는_사유가_redis_error다() {
        // 실제 WRONGTYPE: 대기 공모주 목록(Set) 키에 문자열이 들어 있으면 SMEMBERS가 거부된다
        TEMPLATE.opsForValue().set(QueueRedisRepository.ACTIVE_KEY, "not-a-set").block();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        scheduler(repository, registry, Duration.ofSeconds(5)).run();

        assertThat(failingReasons(registry)).containsExactly("redis_error");
    }

    @Test
    void 실패했다가_다시_성공하면_지표가_1로_돌아온다() {
        AtomicBoolean failing = new AtomicBoolean(true);
        QueueRedisRepository flaky = new QueueRedisRepository(TEMPLATE) {
            @Override
            public Flux<UUID> activeIpoIds() {
                return failing.get() ? Flux.error(new RedisConnectionFailureException("시험용 장애")) : super.activeIpoIds();
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(flaky, registry, Duration.ofSeconds(5));

        scheduler.run();
        assertThat(up(registry)).isZero();
        assertThat(failingReasons(registry)).containsExactly("redis_connection");

        failing.set(false);
        scheduler.run();
        assertThat(up(registry)).isEqualTo(1);
        assertThat(failingReasons(registry)).isEmpty();
    }

    @Test
    void 사유가_바뀌면_이전_사유는_0이_되고_새_사유만_1이다() {
        AtomicReference<RuntimeException> error = new AtomicReference<>(new QueryTimeoutException("시험용 시간 초과"));
        QueueRedisRepository changing = new QueueRedisRepository(TEMPLATE) {
            @Override
            public Flux<UUID> activeIpoIds() {
                return Flux.error(error.get());
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(changing, registry, Duration.ofSeconds(5));

        scheduler.run();
        assertThat(failingReasons(registry)).containsExactly("redis_timeout");

        error.set(new RedisConnectionFailureException("시험용 연결 실패"));
        scheduler.run();
        assertThat(failingReasons(registry)).containsExactly("redis_connection");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 실패가_이어지면_WARN은_시작할_때_한_번이고_복구되면_INFO를_남긴다(CapturedOutput output) {
        AtomicBoolean failing = new AtomicBoolean(true);
        QueueRedisRepository flaky = new QueueRedisRepository(TEMPLATE) {
            @Override
            public Flux<UUID> activeIpoIds() {
                return failing.get() ? Flux.error(new RedisConnectionFailureException("시험용 장애")) : super.activeIpoIds();
            }
        };
        AdmissionScheduler scheduler = scheduler(flaky, new SimpleMeterRegistry(), Duration.ofSeconds(5));

        scheduler.run();
        scheduler.run();
        scheduler.run();
        failing.set(false);
        scheduler.run();

        assertThat(output.getOut().lines().filter(l -> l.contains("WARN") && l.contains("대기열 입장 처리 실패, 사유 redis_connection")))
                .hasSize(1);
        assertThat(output.getOut().lines().filter(l -> l.contains("INFO") && l.contains("대기열 입장 처리 복구"))).hasSize(1);
    }

    @Test
    void 입장_처리가_시간_안에_끝나지_않으면_지표가_0이다() {
        QueueRedisRepository hanging = new QueueRedisRepository(TEMPLATE) {
            @Override
            public Flux<UUID> activeIpoIds() {
                return Flux.never();
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(hanging, registry, Duration.ofMillis(200));

        scheduler.run();

        assertThat(up(registry)).isZero();
        assertThat(failingReasons(registry)).containsExactly("redis_timeout");
    }

    @Test
    void Redis_외의_오류로_입장_처리가_멈춰도_지표가_0이다() {
        QueueRedisRepository broken = new QueueRedisRepository(TEMPLATE) {
            @Override
            public Flux<UUID> activeIpoIds() {
                return Flux.error(new IllegalStateException("시험용 오류"));
            }
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AdmissionScheduler scheduler = scheduler(broken, registry, Duration.ofSeconds(5));

        scheduler.run();

        assertThat(up(registry)).isZero();
        assertThat(failingReasons(registry)).containsExactly("other");
    }

    private AdmissionScheduler scheduler(int admitRate) {
        return new AdmissionScheduler(repository, properties(admitRate), new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(5));
    }

    private AdmissionScheduler scheduler(QueueRedisRepository repo, SimpleMeterRegistry registry, Duration timeout) {
        return new AdmissionScheduler(repo, properties(433), registry, Clock.fixed(NOW, ZoneOffset.UTC), timeout);
    }

    private static QueueProperties properties(int admitRate) {
        return new QueueProperties(Duration.ofSeconds(1), admitRate, Duration.ofMinutes(5), 3, 500, 1000);
    }

    private static double up(SimpleMeterRegistry registry) {
        return registry.get(AdmissionScheduler.UP_METRIC).gauge().value();
    }

    // 값이 1인 실패 사유 라벨 (사유 시계열은 다섯 개가 항상 있다)
    private static List<String> failingReasons(SimpleMeterRegistry registry) {
        Collection<Gauge> gauges = registry.get(AdmissionScheduler.FAILING_METRIC).gauges();
        assertThat(gauges).hasSize(AdmissionFailureReason.values().length);
        return gauges.stream().filter(g -> g.value() == 1).map(g -> g.getId().getTag("reason")).toList();
    }

    private static void setMaxMemory(String bytes) {
        TEMPLATE.execute(connection -> connection.serverCommands().setConfig("maxmemory", bytes)).blockLast();
    }

    private void enterUsers(UUID ipoId, int count) {
        IntStream.range(0, count).forEach(i ->
                repository.enter(ipoId, UUID.randomUUID(), NOW.plusMillis(i), ALIVE).block());
    }

    private long waitingCount(UUID ipoId) {
        return TEMPLATE.opsForZSet().size(QueueRedisRepository.waitingKey(ipoId)).block();
    }
}
