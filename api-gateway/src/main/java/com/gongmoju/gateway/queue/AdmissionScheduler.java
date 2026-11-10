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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 입장 스케줄러. queue.admit-interval마다 대기자가 있는 공모주에 입장 인원을 나눠 앞에서부터 허가를 준다.
 *
 * <p>입장 인원(queue.admit-rate)은 전체 공모주 합이다. 공모주별로 주면 대기자가 있는 공모주 수만큼 서비스 유입이 곱해진다.
 * 나누는 방식은 {@link #allocate}: 대기자가 적은 공모주는 바로 들어가고 남는 자리는 대기자가 많은 공모주로 넘긴다.
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
            // 공모주마다 대기 인원을 먼저 읽어 남는 자리가 버려지지 않게 나눈다
            return Flux.fromIterable(ipoIds).concatMap(repository::waitingCount).collectList().flatMap(counts -> {
                long[] demands = counts.stream().mapToLong(Long::longValue).toArray();
                long[] shares = allocate(properties.admitsPerTick(), demands, offset);
                return Flux.range(0, ipoIds.size())
                        .concatMap(i -> admit(ipoIds.get(i), now, shares[i]))
                        .then();
            });
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
     * 이번 주기의 입장 인원을 공모주들에 나눈다. 남는 자리를 버리지 않고 아직 대기자가 남은 공모주에 넘긴다.
     * <ol>
     *   <li>대기자가 남은 공모주들에 똑같이 나눈다</li>
     *   <li>대기자가 몫보다 적은 공모주는 대기자 수만큼만 가져가고, 남은 자리는 아직 대기자가 남은 공모주에 다시 똑같이 나눈다</li>
     *   <li>똑같이 나누고 남는 몇 자리는 1명씩 주되, 받는 공모주를 주기마다 돌려 한쪽에 몰리지 않게 한다</li>
     * </ol>
     * 예 (433명): 대기 5만 명 + 3명 → 430, 3 / 5만 명 + 5만 명 → 217, 216 (다음 주기 216, 217) / 5만 명 + 1명짜리 10개 → 423, 1 x 10.
     * 대기자가 적은 공모주는 기다리지 않고 바로 들어가고, 대기자가 많은 공모주가 남는 자리를 쓴다.
     * 입장 인원은 서비스가 받을 수 있는 전체 한도라, 어느 공모주가 다 쓰지 못한 자리는 다른 공모주가 써야 한도만큼 처리된다.
     *
     * <p>대기 인원에는 아직 정리되지 않은 이탈자가 들어 있을 수 있다. 그 몫은 입장 처리에서 이탈자를 빼면서 그 주기에만 덜 쓰인다.
     *
     * @param total   이번 주기에 허가할 전체 인원
     * @param demands 공모주별 대기 인원 (순서는 반환값과 같다)
     * @param offset  나머지를 받을 공모주를 돌리는 값 (주기마다 1씩 늘린다)
     * @return 공모주별 허가 인원. 대기 인원을 넘지 않고, 합은 min(total, 대기 인원 합)이다
     */
    static long[] allocate(long total, long[] demands, long offset) {
        long[] shares = new long[demands.length];
        List<Integer> open = new ArrayList<>();
        for (int i = 0; i < demands.length; i++) {
            if (demands[i] > 0) {
                open.add(i);
            }
        }
        long remaining = total;
        while (remaining > 0 && !open.isEmpty()) {
            long each = remaining / open.size();
            if (each == 0) {
                // 남은 자리가 공모주 수보다 적다. 남은 공모주는 모두 1자리 이상 비어 있으므로 1명씩 돌려 준다
                int start = (int) Math.floorMod(offset, (long) open.size());
                for (int k = 0; k < remaining; k++) {
                    shares[open.get((start + k) % open.size())]++;
                }
                break;
            }
            List<Integer> stillOpen = new ArrayList<>();
            for (int i : open) {
                long give = Math.min(each, demands[i] - shares[i]);
                shares[i] += give;
                remaining -= give;
                if (shares[i] < demands[i]) {
                    stillOpen.add(i);
                }
            }
            open = stillOpen;
        }
        return shares;
    }
}
