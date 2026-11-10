package com.gongmoju.gateway.queue;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 입장 스케줄러. queue.admit-interval마다 대기자가 있는 공모주에 입장 인원을 나눠 앞에서부터 허가를 준다.
 *
 * <p>입장 인원(queue.admit-rate)은 전체 공모주 합이다. 공모주별로 주면 대기자가 있는 공모주 수만큼 서비스 유입이 곱해진다.
 * 게이트웨이 1대 전제: 여러 대면 대수만큼 입장시킨다 (스크립트가 원자라 같은 사람이 두 번 허가받지는 않는다).
 *
 * <p>입장 처리 결과를 지표 두 개로 내보낸다. 대기자가 없어도 매 주기 Redis를 조회하므로 요청이 없는 시간의 Redis 장애도 잡힌다.
 * <ul>
 *   <li>{@value #UP_METRIC} (Prometheus {@code queue_admission_up}): 마지막 입장 처리가 성공하면 1, 실패하면 0</li>
 *   <li>{@value #FAILING_METRIC} (Prometheus {@code queue_admission_failing{reason=...}}): 마지막 실패의 사유만 1, 나머지 사유와 성공 시는 0.
 *       알림 QueueAdmissionDown이 이 값으로 사유별 문구를 보낸다 ({@link AdmissionFailureReason})</li>
 * </ul>
 * 원인을 Redis로 좁히지 않는다: 어떤 이유로든 입장 처리가 멈추면 아무도 허가를 받지 못해 모든 청약 신청이 막히기 때문이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "queue.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionScheduler {

    static final String UP_METRIC = "queue.admission.up";
    static final String FAILING_METRIC = "queue.admission.failing";

    // 한 번의 입장 처리가 이보다 오래 걸리면 포기하고 다음 주기에 다시 한다 (Redis 장애 시 스케줄러 스레드가 묶이지 않게)
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final QueueRedisRepository repository;
    private final QueueProperties properties;
    private final Clock clock;
    private final Duration timeout;
    // 아직 한 번도 성공하지 않았으면 0 (첫 주기는 기동 직후 바로 돌아서 정상이면 곧 1이 된다)
    private final AtomicInteger up = new AtomicInteger(0);
    private final Map<AdmissionFailureReason, AtomicInteger> failing = new EnumMap<>(AdmissionFailureReason.class);
    // 직전 주기의 실패 사유. 성공이면 null (스케줄러 스레드 하나만 읽고 쓴다)
    private AdmissionFailureReason currentFailure;
    private long tick;

    @Autowired
    public AdmissionScheduler(QueueRedisRepository repository, QueueProperties properties, MeterRegistry meterRegistry) {
        this(repository, properties, meterRegistry, Clock.systemUTC(), TIMEOUT);
    }

    AdmissionScheduler(QueueRedisRepository repository, QueueProperties properties, MeterRegistry meterRegistry,
                       Clock clock, Duration timeout) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
        this.timeout = timeout;
        Gauge.builder(UP_METRIC, up, AtomicInteger::get)
                .description("대기열 입장 처리 성공 여부 (마지막 주기 성공 1, 실패 0)")
                .register(meterRegistry);
        // 사유마다 시계열을 처음부터 0으로 만들어 둔다 (실패가 처음 날 때 시계열이 새로 생기면 알림 평가가 한 주기 늦어진다)
        for (AdmissionFailureReason reason : AdmissionFailureReason.values()) {
            AtomicInteger value = new AtomicInteger(0);
            failing.put(reason, value);
            Gauge.builder(FAILING_METRIC, value, AtomicInteger::get)
                    .description("대기열 입장 처리 실패 사유 (마지막 주기의 실패 사유만 1)")
                    .tag("reason", reason.tag())
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${queue.admit-interval:PT1S}")
    public void run() {
        try {
            // block(Duration)의 시간 초과는 IllegalStateException이라 사유를 가리기 어려워 timeout 연산자로 TimeoutException을 낸다
            admitOnce().timeout(timeout).block();
            recordSuccess();
        } catch (RuntimeException e) {
            recordFailure(e);
        }
    }

    private void recordSuccess() {
        up.set(1);
        failing.values().forEach(value -> value.set(0));
        if (currentFailure != null) {
            log.info("대기열 입장 처리 복구 (직전 실패 사유 {})", currentFailure.tag());
            currentFailure = null;
        }
    }

    // 장애 중에는 1초마다 실패하므로 실패가 시작되거나 사유가 바뀔 때만 WARN으로 남기고, 같은 사유가 이어지면 DEBUG로 남긴다
    private void recordFailure(RuntimeException e) {
        AdmissionFailureReason reason = AdmissionFailureReason.classify(e);
        up.set(0);
        failing.forEach((r, value) -> value.set(r == reason ? 1 : 0));
        if (reason != currentFailure) {
            log.warn("대기열 입장 처리 실패, 사유 {} (같은 사유가 이어지면 DEBUG로 남김): {}", reason.tag(), e.toString());
            currentFailure = reason;
        } else {
            log.debug("대기열 입장 처리 실패, 사유 {}: {}", reason.tag(), e.toString());
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
