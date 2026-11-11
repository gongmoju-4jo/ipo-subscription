package com.gongmoju.gateway.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 대기열 Lua 스크립트를 실제 Redis(redis:7)로 확인한다
class QueueRedisRepositoryTest extends RedisTestSupport {

    private static final Instant NOW = Instant.parse("2026-11-09T07:00:00.000Z");
    private static final Instant ALIVE = NOW.plusSeconds(60);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final QueueRedisRepository repository = new QueueRedisRepository(TEMPLATE);
    private final UUID ipoId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        flushAll();
    }

    @Test
    void 처음_진입하면_대기_순번_1이고_새로_진입한_것으로_나온다() {
        QueueState state = enter(UUID.randomUUID(), NOW);

        assertThat(state.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(state.position()).isEqualTo(1);
        assertThat(state.waitingCount()).isEqualTo(1);
        assertThat(state.enteredAt()).isEqualTo(NOW);
        assertThat(state.admissionTtl()).isNull();
        assertThat(state.created()).isTrue();
    }

    @Test
    void 진입한_순서대로_순번이_매겨진다() {
        enter(UUID.randomUUID(), NOW);
        enter(UUID.randomUUID(), NOW.plusMillis(1));
        QueueState third = enter(UUID.randomUUID(), NOW.plusMillis(2));

        assertThat(third.position()).isEqualTo(3);
        assertThat(third.waitingCount()).isEqualTo(3);
    }

    @Test
    void 다시_진입해도_처음_순번과_진입_시각을_유지한다() {
        UUID first = UUID.randomUUID();
        enter(first, NOW);
        enter(UUID.randomUUID(), NOW.plusMillis(1));

        QueueState again = enter(first, NOW.plusSeconds(10));

        assertThat(again.position()).isEqualTo(1);
        assertThat(again.enteredAt()).isEqualTo(NOW);
        assertThat(again.created()).isFalse();
    }

    @Test
    void 대기_중이_아니면_조회_결과가_없다() {
        assertThat(repository.find(ipoId, UUID.randomUUID(), ALIVE).blockOptional()).isEmpty();
    }

    @Test
    void 공모주마다_대기열이_따로다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);

        QueueState other = repository.enter(UUID.randomUUID(), userId, NOW, ALIVE).block();

        assertThat(other.position()).isEqualTo(1);
        assertThat(other.created()).isTrue();
    }

    @Test
    void 입장_처리는_앞에서부터_정해진_인원만_허가한다() {
        UUID[] users = enterUsers(5);

        AdmitResult result = repository.admit(ipoId, NOW, 2, TTL, 1000).block();

        assertThat(result).isEqualTo(new AdmitResult(2, 0, 3));
        assertThat(find(users[0]).status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(find(users[1]).status()).isEqualTo(QueueStatus.ADMITTED);
        QueueState third = find(users[2]);
        assertThat(third.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(third.position()).isEqualTo(1);
        assertThat(third.waitingCount()).isEqualTo(3);
    }

    @Test
    void 허가_상태는_남은_시간과_처음_진입_시각을_돌려준다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        repository.admit(ipoId, NOW, 1, TTL, 1000).block();

        QueueState state = find(userId);

        assertThat(state.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(state.position()).isZero();
        assertThat(state.enteredAt()).isEqualTo(NOW);
        assertThat(state.admissionTtl()).isPositive().isLessThanOrEqualTo(TTL);
    }

    @Test
    void 허가받은_사람이_다시_진입하면_대기열에_다시_넣지_않고_허가_상태를_돌려준다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        repository.admit(ipoId, NOW, 1, TTL, 1000).block();

        QueueState again = enter(userId, NOW.plusSeconds(1));

        assertThat(again.status()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(again.created()).isFalse();
        assertThat(again.waitingCount()).isZero();
    }

    @Test
    void 허가는_유효_시간이_지나면_사라진다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        repository.admit(ipoId, NOW, 1, Duration.ofMillis(200), 1000).block();

        Thread.sleep(400);

        assertThat(repository.find(ipoId, userId, ALIVE).blockOptional()).isEmpty();
    }

    @Test
    void 허가가_만료된_뒤_다시_진입하면_대기열_맨_뒤로_간다() throws InterruptedException {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        repository.admit(ipoId, NOW, 1, Duration.ofMillis(200), 1000).block();
        enter(UUID.randomUUID(), NOW.plusSeconds(1));
        Thread.sleep(400);

        QueueState again = enter(userId, NOW.plusSeconds(2));

        assertThat(again.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(again.position()).isEqualTo(2);
        assertThat(again.created()).isTrue();
    }

    @Test
    void 이탈_기한이_지난_사람은_허가받지_않고_이탈_처리된다() {
        UUID gone = UUID.randomUUID();
        UUID alive = UUID.randomUUID();
        repository.enter(ipoId, gone, NOW, NOW.minusMillis(1)).block();
        repository.enter(ipoId, alive, NOW.plusMillis(1), ALIVE).block();

        AdmitResult result = repository.admit(ipoId, NOW, 1, TTL, 1000).block();

        assertThat(result).isEqualTo(new AdmitResult(1, 1, 0));
        assertThat(repository.find(ipoId, gone, ALIVE).blockOptional()).isEmpty();
        assertThat(find(alive).status()).isEqualTo(QueueStatus.ADMITTED);
    }

    @Test
    void 순서를_조회하면_이탈_기한이_늘어난다() {
        UUID userId = UUID.randomUUID();
        repository.enter(ipoId, userId, NOW, NOW.plusSeconds(3)).block();

        repository.find(ipoId, userId, NOW.plusSeconds(60)).block();
        AdmitResult result = repository.admit(ipoId, NOW.plusSeconds(10), 0, TTL, 1000).block();

        assertThat(result).isEqualTo(new AdmitResult(0, 0, 1));
        assertThat(find(userId).status()).isEqualTo(QueueStatus.WAITING);
    }

    @Test
    void 이탈자_정리는_한_번에_상한만큼만_한다() {
        for (int i = 0; i < 3; i++) {
            repository.enter(ipoId, UUID.randomUUID(), NOW.plusMillis(i), NOW.minusMillis(1)).block();
        }

        AdmitResult first = repository.admit(ipoId, NOW, 0, TTL, 2).block();
        AdmitResult second = repository.admit(ipoId, NOW, 0, TTL, 2).block();

        assertThat(first).isEqualTo(new AdmitResult(0, 2, 1));
        assertThat(second).isEqualTo(new AdmitResult(0, 1, 0));
    }

    @Test
    void 대기_중에_이탈하면_대기열에서_빠진다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        UUID next = UUID.randomUUID();
        enter(next, NOW.plusMillis(1));

        repository.leave(ipoId, userId).block();

        assertThat(repository.find(ipoId, userId, ALIVE).blockOptional()).isEmpty();
        assertThat(find(next).position()).isEqualTo(1);
    }

    @Test
    void 허가_상태에서_이탈하면_허가도_사라진다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);
        repository.admit(ipoId, NOW, 1, TTL, 1000).block();

        repository.leave(ipoId, userId).block();

        assertThat(repository.find(ipoId, userId, ALIVE).blockOptional()).isEmpty();
    }

    @Test
    void 대기_중이_아닌데_이탈해도_성공한다() {
        repository.leave(ipoId, UUID.randomUUID()).block();
    }

    @Test
    void 진입하면_입장_처리_대상이_되고_대기자가_0명이_되면_빠진다() {
        enterUsers(2);
        assertThat(repository.activeIpoIds().collectList().block()).containsExactly(ipoId);

        repository.admit(ipoId, NOW, 2, TTL, 1000).block();

        assertThat(repository.activeIpoIds().collectList().block()).isEmpty();
    }

    @Test
    void 대문자로_받은_UUID도_같은_키가_된다() {
        UUID userId = UUID.randomUUID();
        enter(userId, NOW);

        UUID upperIpo = UUID.fromString(ipoId.toString().toUpperCase());
        UUID upperUser = UUID.fromString(userId.toString().toUpperCase());

        assertThat(repository.find(upperIpo, upperUser, ALIVE).block().position()).isEqualTo(1);
    }

    private QueueState enter(UUID userId, Instant at) {
        return repository.enter(ipoId, userId, at, ALIVE).block();
    }

    private QueueState find(UUID userId) {
        return repository.find(ipoId, userId, ALIVE).block();
    }

    private UUID[] enterUsers(int count) {
        UUID[] users = new UUID[count];
        for (int i = 0; i < count; i++) {
            users[i] = UUID.randomUUID();
            enter(users[i], NOW.plusMillis(i));
        }
        return users;
    }
}
