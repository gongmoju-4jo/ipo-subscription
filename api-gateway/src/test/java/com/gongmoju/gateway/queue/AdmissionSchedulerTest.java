package com.gongmoju.gateway.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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

    private AdmissionScheduler scheduler(int admitRate) {
        QueueProperties properties = new QueueProperties(Duration.ofSeconds(1), admitRate, Duration.ofMinutes(5), 3, 500, 1000);
        return new AdmissionScheduler(repository, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void enterUsers(UUID ipoId, int count) {
        IntStream.range(0, count).forEach(i ->
                repository.enter(ipoId, UUID.randomUUID(), NOW.plusMillis(i), ALIVE).block());
    }

    private long waitingCount(UUID ipoId) {
        return TEMPLATE.opsForZSet().size(QueueRedisRepository.waitingKey(ipoId)).block();
    }
}
