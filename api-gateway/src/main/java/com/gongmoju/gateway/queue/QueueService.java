package com.gongmoju.gateway.queue;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 대기열 진입, 순서 조회, 이탈.
 *
 * <p>순서 조회 간격은 대기 인원에 비례해 늘린다: 간격 = max(1초, 대기 인원 ÷ 조회 예산).
 * 대기자 전원이 이 간격으로 조회하면 초당 조회 수가 조회 예산을 넘지 않는다 (예: 대기 1만 명, 예산 500 → 20초 간격).
 * 이탈 기한은 이번에 알려준 간격 × 이탈 배수다. 간격이 길어져도 정상 대기자를 이탈로 처리하지 않게 고정 시간을 쓰지 않는다.
 */
@Service
public class QueueService {

    private static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(1);

    private final QueueRedisRepository repository;
    private final QueueProperties properties;
    private final Clock clock;

    @Autowired
    public QueueService(QueueRedisRepository repository, QueueProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    QueueService(QueueRedisRepository repository, QueueProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 대기열에 진입한다. 이미 줄에 있으면 처음 순번을 유지하고, 이미 입장 허가를 받았으면 허가 상태를 돌려준다.
     */
    public Mono<QueueView> enter(UUID ipoId, UUID userId) {
        Instant now = clock.instant();
        // 간격은 나를 포함한 인원으로 정한다 (새로 줄에 서는 경우)
        return repository.waitingCount(ipoId).flatMap(count -> {
            Duration interval = pollInterval(count + 1);
            return repository.enter(ipoId, userId, now, now.plus(interval.multipliedBy(properties.dropoutFactor())))
                    .map(state -> view(ipoId, state, now, interval));
        });
    }

    /**
     * 순서 조회 간격 = max(1초, 대기 인원 ÷ 조회 예산).
     */
    Duration pollInterval(long waitingCount) {
        long millis = Math.ceilDiv(waitingCount * 1000, properties.pollBudget());
        return Duration.ofMillis(Math.max(MIN_POLL_INTERVAL.toMillis(), millis));
    }

    private QueueView view(UUID ipoId, QueueState state, Instant now, Duration interval) {
        boolean admitted = state.status() == QueueStatus.ADMITTED;
        return new QueueView(
                ipoId,
                state.status(),
                state.position(),
                state.waitingCount(),
                admitted ? 0 : Math.ceilDiv(state.position(), properties.admitRate()),
                state.enteredAt(),
                // 다른 시각 필드처럼 밀리초까지만 내려준다
                admitted ? now.plus(state.admissionTtl()).truncatedTo(ChronoUnit.MILLIS) : null,
                interval,
                state.created());
    }
}
