package com.gongmoju.gateway.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 입장 스케줄러. queue.admit-interval마다 대기자가 있는 공모주에 입장 인원을 나눠 앞에서부터 허가를 준다.
 *
 * <p>입장 인원(queue.admit-rate)은 전체 공모주 합이다. 공모주별로 주면 대기자가 있는 공모주 수만큼 서비스 유입이 곱해진다.
 * 게이트웨이 1대 전제: 여러 대면 대수만큼 입장시킨다 (스크립트가 원자라 같은 사람이 두 번 허가받지는 않는다).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "queue.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionScheduler {

    // 한 번의 입장 처리가 이보다 오래 걸리면 포기하고 다음 주기에 다시 한다 (Redis 장애 시 스케줄러 스레드가 묶이지 않게)
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final QueueRedisRepository repository;
    private final QueueProperties properties;
    private final Clock clock;
    private long tick;

    @Autowired
    public AdmissionScheduler(QueueRedisRepository repository, QueueProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    AdmissionScheduler(QueueRedisRepository repository, QueueProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${queue.admit-interval:PT1S}")
    public void run() {
        try {
            admitOnce().block(TIMEOUT);
        } catch (RuntimeException e) {
            // Redis 장애 등. 대기열 API와 청약 신청도 같은 원인으로 503이 되므로 여기서는 기록만 하고 다음 주기에 다시 한다
            log.warn("대기열 입장 처리 실패: {}", e.toString());
        }
    }

    Mono<Void> admitOnce() {
        Instant now = clock.instant();
        long offset = tick++;
        return repository.activeIpoIds().collectList().flatMap(ipoIds -> {
            if (ipoIds.isEmpty()) {
                return Mono.empty();
            }
            long[] shares = split(properties.admitsPerTick(), ipoIds.size(), offset);
            return Flux.range(0, ipoIds.size())
                    .concatMap(i -> admit(ipoIds.get(i), now, shares[i]))
                    .then();
        });
    }

    private Mono<AdmitResult> admit(UUID ipoId, Instant now, long count) {
        return repository.admit(ipoId, now, count, properties.admissionTtl(), properties.dropoutBatchSize())
                .doOnNext(result -> {
                    if (result.admitted() > 0 || result.droppedOut() > 0) {
                        log.debug("대기열 입장 ipoId={} 허가={} 이탈 정리={} 남은 대기={}",
                                ipoId, result.admitted(), result.droppedOut(), result.remaining());
                    }
                });
    }

    /**
     * 입장 인원을 공모주 수로 나눈다. 나누어떨어지지 않는 나머지는 1명씩 주되, 받는 공모주를 주기마다 돌려 한쪽에 몰리지 않게 한다.
     * 예: 433명, 공모주 2개 → 217, 216 (다음 주기에는 216, 217)
     */
    static long[] split(long total, int count, long offset) {
        long[] shares = new long[count];
        long base = total / count;
        long remainder = total % count;
        for (int i = 0; i < count; i++) {
            shares[i] = base;
        }
        for (long r = 0; r < remainder; r++) {
            shares[(int) ((offset + r) % count)]++;
        }
        return shares;
    }
}
