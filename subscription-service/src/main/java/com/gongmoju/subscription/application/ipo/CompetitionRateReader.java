package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 공모주들의 경쟁률(F-15)을 계산한다. 배정 대상 청약({@link SubscriptionStatus#ALLOCATION_TARGETS}: 확정, 배정 완료, 환불 완료 청약)의
 * 신청 수량 합은 사본({@link CompetitionRateCounter}, Redis)에서 읽고, 계산 규칙은 {@link Ipo#competitionRate}가 정한다.
 * <p>
 * 사본이 없는 공모주(처음, Redis 재시작, 유효 시간 만료, Redis 장애)는 DB에서 한 번의 조회로 합산하고 사본에 넣는다.
 * 같은 공모주를 여러 요청이 동시에 다시 계산하지 않도록, 이 서비스 안에서는 공모주마다 한 요청만 DB로 계산하고 나머지는 그 결과를 기다린다
 * (사본이 만료되는 순간 몰린 조회가 모두 DB 합산을 하면 PostgreSQL이 막히기 때문. S7 예비 측정).
 * <p>
 * 트랜잭션 밖에서 부른다. 기다리는 요청이 DB 커넥션을 붙잡지 않게 하려는 것이다 (DB 합산은 저장소 호출 하나라 그 동안만 커넥션을 씀).
 */
@Component
@RequiredArgsConstructor
class CompetitionRateReader {

    // 다른 요청의 DB 합산을 기다리는 최대 시간. 넘으면 그 계산을 더 기다리지 않고 직접 합산한다 (부하 중 10만 건 합산이 약 0.9초)
    private static final long WAIT_MILLIS = 10_000;

    private final CompetitionRateCounter counter;
    private final SubscriptionRepository subscriptionRepository;
    // 공모주 ID → DB로 계산 중인 신청 수량 합 (계산이 끝나면 지움)
    private final Map<UUID, CompletableFuture<Long>> loading = new ConcurrentHashMap<>();
    // 테스트가 짧게 바꿀 수 있게 필드로 둔다 (생성자 주입 대상 아님)
    private long waitMillis = WAIT_MILLIS;

    void setWaitMillisForTest(long waitMillis) {
        this.waitMillis = waitMillis;
    }

    /**
     * @return 공모주 ID → 경쟁률. 해당 청약이 없는 공모주는 0.00
     */
    Map<UUID, BigDecimal> rates(List<Ipo> ipos) {
        List<UUID> ids = ipos.stream().map(Ipo::getId).toList();
        Map<UUID, Long> requested = new HashMap<>(counter.find(ids));
        List<UUID> missing = ids.stream().filter(id -> !requested.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            requested.putAll(load(missing, true));
        }
        Map<UUID, BigDecimal> rates = new HashMap<>();
        for (Ipo ipo : ipos) {
            rates.put(ipo.getId(), ipo.competitionRate(requested.getOrDefault(ipo.getId(), 0L)));
        }
        return rates;
    }

    // 사본이 없는 공모주의 신청 수량 합을 DB로 계산한다. 다른 요청이 계산 중인 공모주는 그 결과를 기다린다.
    // 그 계산이 실패하면 한 번만 다시 이 경로(한 요청만 계산)로 시도하고 (실패한 순간 기다리던 요청이 모두 DB로 몰리지 않게),
    // 기다리는 시간을 넘으면 그 계산은 그대로 두고 이 요청만 DB로 직접 합산한다 (같은 계산에 다시 합류하면 또 기다리게 되므로)
    private Map<UUID, Long> load(List<UUID> missing, boolean retryOnFailure) {
        Map<UUID, CompletableFuture<Long>> mine = new LinkedHashMap<>();
        Map<UUID, CompletableFuture<Long>> others = new LinkedHashMap<>();
        for (UUID id : missing) {
            CompletableFuture<Long> created = new CompletableFuture<>();
            CompletableFuture<Long> existing = loading.putIfAbsent(id, created);
            if (existing == null) {
                mine.put(id, created);
            } else {
                others.put(id, existing);
            }
        }
        Map<UUID, Long> result = new HashMap<>();
        if (!mine.isEmpty()) {
            result.putAll(loadOwn(mine));
        }
        List<UUID> failed = new ArrayList<>();
        List<UUID> timedOut = new ArrayList<>();
        for (Map.Entry<UUID, CompletableFuture<Long>> e : others.entrySet()) {
            try {
                result.put(e.getKey(), e.getValue().get(waitMillis, TimeUnit.MILLISECONDS));
            } catch (ExecutionException failure) {
                failed.add(e.getKey());
            } catch (TimeoutException slow) {
                timedOut.add(e.getKey());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("경쟁률 계산을 기다리다 중단됨", interrupted);
            }
        }
        if (!timedOut.isEmpty()) {
            // 사본은 넣지 않는다 (채우기는 기다리던 계산이 맡고 있음)
            Map<UUID, Long> sums = subscriptionRepository.sumRequestedQuantityByIpoIds(timedOut, SubscriptionStatus.ALLOCATION_TARGETS);
            timedOut.forEach(id -> result.put(id, sums.getOrDefault(id, 0L)));
        }
        if (!failed.isEmpty()) {
            if (!retryOnFailure) {
                throw new IllegalStateException("경쟁률을 DB로 계산하지 못함. ipoIds=" + failed);
            }
            result.putAll(load(failed, false));
        }
        return result;
    }

    // 이 요청이 맡은 공모주를 DB로 합산한다. 어떤 오류가 나도 기다리는 요청이 풀리게 결과를 완료 처리한다.
    // 완료하기 전에 계산 중 목록에서 먼저 지운다 (기다리던 요청이 실패를 받고 다시 시도할 때 끝난 계산을 또 기다리지 않게)
    private Map<UUID, Long> loadOwn(Map<UUID, CompletableFuture<Long>> mine) {
        Map<UUID, Long> loaded;
        try {
            // 채우는 중 표시를 먼저 만들어, DB 합산 동안 확정된 청약이 사본에서 빠지지 않게 한다 (다른 인스턴스가 채우는 중인 공모주는 넣지 않음)
            Set<UUID> owned = counter.startLoading(mine.keySet());
            Map<UUID, Long> sums = subscriptionRepository.sumRequestedQuantityByIpoIds(mine.keySet(), SubscriptionStatus.ALLOCATION_TARGETS);
            loaded = new HashMap<>();
            for (UUID id : mine.keySet()) {
                loaded.put(id, sums.getOrDefault(id, 0L));
            }
            Map<UUID, Long> toStore = new HashMap<>();
            owned.forEach(id -> toStore.put(id, loaded.get(id)));
            counter.finishLoading(toStore);
        } catch (Throwable t) {
            mine.forEach(loading::remove);
            mine.values().forEach(future -> future.completeExceptionally(t));
            throw t;
        }
        mine.forEach(loading::remove);
        mine.forEach((id, future) -> future.complete(loaded.get(id)));
        return loaded;
    }
}
