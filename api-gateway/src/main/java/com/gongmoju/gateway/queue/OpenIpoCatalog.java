package com.gongmoju.gateway.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 청약 중 공모주 목록. 청약 서비스에서 주기적으로 받아 메모리에 두고, 대기열 진입 때 청약 중인 공모주인지 확인한다.
 * 진입마다 청약 서비스를 부르지 않기 위해서다 (접속이 몰리는 경로에 동기 호출을 넣지 않음).
 *
 * <ul>
 *   <li>켜지면 바로 받고, 받을 때까지 queue.open-ipos.check-interval(1초)마다 다시 시도한다. 받은 뒤에는 refresh-interval(5초)마다 갱신한다</li>
 *   <li>갱신에 실패하면 마지막 목록을 계속 쓴다. 청약 기간을 시각으로 판단하므로 마감은 그대로 정확하다</li>
 *   <li>한 번도 받지 못했거나 stale-after(30초) 동안 갱신하지 못했으면 확인 없이 진입을 허용한다.
 *       이 확인은 없는 공모주 번호로 대기열을 만드는 것을 막는 용도이고, 청약 신청의 입장 허가 확인은 그대로라서
 *       청약 서비스의 이 API 하나 때문에 대기열 전체나 새로 열린 공모주의 진입이 막히지 않게 한다</li>
 * </ul>
 * 게이트웨이 1대 기준 청약 서비스 호출은 5초에 1번이다.
 */
@Slf4j
@Component
public class OpenIpoCatalog {

    /** 진입 확인 결과 */
    enum Check {
        /** 청약 중 */
        OPEN,
        /** 목록에 없거나 청약 기간 밖 */
        NOT_OPEN,
        /** 목록이 없거나 오래돼 확인하지 않음 (진입 허용) */
        UNCHECKED
    }

    private final OpenIpoSource source;
    private final OpenIpoProperties properties;
    private final Clock clock;

    // 마지막으로 받은 목록. 한 번도 받지 못했으면 null
    private volatile Snapshot snapshot;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    // 직전 갱신이 실패였는지 (실패가 이어질 때 로그를 한 번만 남기려고)
    private volatile boolean failing;

    @Autowired
    public OpenIpoCatalog(OpenIpoSource source, OpenIpoProperties properties) {
        this(source, properties, Clock.systemUTC());
    }

    OpenIpoCatalog(OpenIpoSource source, OpenIpoProperties properties, Clock clock) {
        this.source = source;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 공모주가 지금 청약 중인지 확인한다. 청약 기간은 시작 시각 이상, 마감 시각 미만이다.
     */
    Check check(UUID ipoId, Instant now) {
        Snapshot current = snapshot;
        if (current == null || now.isAfter(current.loadedAt().plus(properties.staleAfter()))) {
            return Check.UNCHECKED;
        }
        OpenIpoSource.OpenIpo ipo = current.ipos().get(ipoId);
        if (ipo == null || now.isBefore(ipo.subscriptionStartAt()) || !now.isBefore(ipo.subscriptionEndAt())) {
            return Check.NOT_OPEN;
        }
        return Check.OPEN;
    }

    /**
     * 갱신할 때가 됐으면 목록을 다시 받는다. 받는 동안 스케줄러 스레드를 붙잡지 않게 비동기로 시작만 한다
     * (스케줄러 스레드는 입장 처리와 함께 쓴다).
     */
    @Scheduled(fixedDelayString = "${queue.open-ipos.check-interval:PT1S}")
    public void refreshIfDue() {
        Snapshot current = snapshot;
        if (current == null || !clock.instant().isBefore(current.loadedAt().plus(properties.refreshInterval()))) {
            refresh().subscribe();
        }
    }

    /**
     * 목록을 받아 바꾼다. 이미 받는 중이면 아무것도 하지 않는다. 실패해도 오류를 내지 않고 마지막 목록을 유지한다.
     */
    Mono<Void> refresh() {
        if (!inFlight.compareAndSet(false, true)) {
            return Mono.empty();
        }
        return source.fetch()
                .doOnNext(this::replace)
                .doOnError(e -> {
                    if (!failing) {
                        failing = true;
                        log.warn("청약 중 공모주 목록을 받지 못했습니다 (마지막 목록을 계속 쓰고, {} 동안 갱신하지 못하면 확인 없이 진입 허용): {}",
                                properties.staleAfter(), e.toString());
                    }
                })
                .onErrorResume(e -> Mono.empty())
                .doFinally(signal -> inFlight.set(false))
                .then();
    }

    private void replace(List<OpenIpoSource.OpenIpo> ipos) {
        boolean first = snapshot == null;
        snapshot = new Snapshot(ipos.stream().collect(Collectors.toUnmodifiableMap(OpenIpoSource.OpenIpo::ipoId, Function.identity(), (a, b) -> b)),
                clock.instant());
        if (first || failing) {
            log.info("청약 중 공모주 목록 {} ({}개)", first ? "받음" : "갱신 복구", ipos.size());
        }
        failing = false;
    }

    private record Snapshot(Map<UUID, OpenIpoSource.OpenIpo> ipos, Instant loadedAt) {
    }
}
