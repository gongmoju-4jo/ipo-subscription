package com.gongmoju.gateway.queue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.gongmoju.gateway.queue.OpenIpoCatalog.Check.NOT_OPEN;
import static com.gongmoju.gateway.queue.OpenIpoCatalog.Check.OPEN;
import static com.gongmoju.gateway.queue.OpenIpoCatalog.Check.UNCHECKED;
import static org.assertj.core.api.Assertions.assertThat;

// 청약 중 공모주 목록: 진입 확인 규칙(기간, 없는 번호), 받지 못했을 때와 오래됐을 때, 갱신 실패 시 마지막 목록 유지, 갱신 주기
class OpenIpoCatalogTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");
    private static final UUID IPO = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final OpenIpoProperties PROPERTIES = new OpenIpoProperties("http://subscription-service", false,
            Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(30));

    private final MutableClock clock = new MutableClock(NOW);
    private final AtomicReference<Mono<List<OpenIpoSource.OpenIpo>>> next = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final OpenIpoSource source = () -> {
        calls.incrementAndGet();
        return next.get();
    };
    private final OpenIpoCatalog catalog = new OpenIpoCatalog(source, PROPERTIES, clock);

    @Test
    void 목록을_받기_전에는_확인하지_않고_진입을_허용한다() {
        assertThat(catalog.check(IPO, NOW)).isEqualTo(UNCHECKED);
        assertThat(catalog.check(UUID.randomUUID(), NOW)).isEqualTo(UNCHECKED);
    }

    @Test
    void 청약_기간은_시작_시각_이상_마감_시각_미만이다() {
        // 목록을 받은 뒤 30초(stale-after) 안에서 확인한다
        load(open(IPO, NOW, NOW.plusSeconds(20)));

        assertThat(catalog.check(IPO, NOW.minusMillis(1))).isEqualTo(NOT_OPEN);
        assertThat(catalog.check(IPO, NOW)).isEqualTo(OPEN);
        assertThat(catalog.check(IPO, NOW.plusSeconds(20).minusMillis(1))).isEqualTo(OPEN);
        assertThat(catalog.check(IPO, NOW.plusSeconds(20))).isEqualTo(NOT_OPEN);
    }

    @Test
    void 목록에_없는_공모주는_청약_중이_아니다() {
        load(open(IPO, NOW.minusSeconds(60), NOW.plusSeconds(60)));

        assertThat(catalog.check(UUID.randomUUID(), NOW)).isEqualTo(NOT_OPEN);
    }

    @Test
    void 받은_지_오래된_목록은_쓰지_않고_확인_없이_허용한다() {
        load(open(IPO, NOW.minusSeconds(60), NOW.plusSeconds(3600)));
        UUID unknown = UUID.randomUUID();

        assertThat(catalog.check(unknown, NOW.plusSeconds(30))).isEqualTo(NOT_OPEN);
        // 30초(stale-after)를 넘기면 새로 열린 공모주가 막히지 않도록 확인하지 않는다
        assertThat(catalog.check(unknown, NOW.plusSeconds(30).plusMillis(1))).isEqualTo(UNCHECKED);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void 갱신에_실패하면_마지막_목록을_유지하고_실패가_이어져도_WARN은_한_번이다(CapturedOutput output) {
        load(open(IPO, NOW.minusSeconds(60), NOW.plusSeconds(3600)));

        next.set(Mono.error(new IllegalStateException("시험용 장애")));
        catalog.refresh().block();
        catalog.refresh().block();

        assertThat(catalog.check(IPO, NOW)).isEqualTo(OPEN);
        assertThat(catalog.check(UUID.randomUUID(), NOW)).isEqualTo(NOT_OPEN);
        assertThat(output.getOut().lines().filter(l -> l.contains("WARN") && l.contains("청약 중 공모주 목록을 받지 못했습니다"))).hasSize(1);

        next.set(Mono.just(List.of()));
        catalog.refresh().block();
        assertThat(catalog.check(IPO, NOW)).isEqualTo(NOT_OPEN);
        assertThat(output.getOut()).contains("청약 중 공모주 목록 갱신 복구 (0개)");
    }

    @Test
    void 받을_때까지는_매번_갱신하고_받은_뒤에는_갱신_주기마다_한다() {
        next.set(Mono.error(new IllegalStateException("시험용 장애")));
        catalog.refreshIfDue();
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(2);

        next.set(Mono.just(List.of(open(IPO, NOW, NOW.plusSeconds(60)))));
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(3);

        clock.set(NOW.plusSeconds(4));
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(3);

        clock.set(NOW.plusSeconds(5));
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void 받는_중에는_다시_요청하지_않는다() {
        Sinks.One<List<OpenIpoSource.OpenIpo>> pending = Sinks.one();
        next.set(pending.asMono());
        catalog.refreshIfDue();
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(1);

        pending.tryEmitValue(List.of());
        catalog.refreshIfDue();
        assertThat(calls.get()).isEqualTo(1);
    }

    private void load(OpenIpoSource.OpenIpo... ipos) {
        next.set(Mono.just(List.of(ipos)));
        catalog.refresh().block();
    }

    private static OpenIpoSource.OpenIpo open(UUID ipoId, Instant start, Instant end) {
        return new OpenIpoSource.OpenIpo(ipoId, start, end);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
