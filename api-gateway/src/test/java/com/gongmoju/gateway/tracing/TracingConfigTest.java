package com.gongmoju.gateway.tracing;

import brave.Tracing;
import brave.sampler.Sampler;
import io.micrometer.observation.transport.Kind;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.W3CPropagation;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TracingConfigTest {

    private final ObservationRegistry registry = registry();

    @Test
    void 부모_없는_클라이언트_호출은_관측하지_않는다() {
        // 입장 스케줄러의 Redis 명령, 공모주 목록 갱신 WebClient
        Observation call = Observation.createNotStarted("http.client.requests",
                () -> new SenderContext<>((carrier, key, value) -> { }), registry);

        assertThat(call.isNoop()).isTrue();
    }

    @Test
    void 밖에서_들어온_요청은_관측한다() {
        Observation request = Observation.createNotStarted("http.server.requests",
                () -> new ReceiverContext<>((carrier, key) -> null), registry);

        assertThat(request.isNoop()).isFalse();
    }

    @Test
    void 게이트웨이의_서비스_호출은_만들_때_부모가_없어도_관측한다() {
        Observation route = Observation.createNotStarted("spring.cloud.gateway.http.client.requests",
                () -> new SenderContext<>((carrier, key, value) -> { }), registry);

        assertThat(route.isNoop()).isFalse();
    }

    @Test
    void 관측하지_않는_작업_안의_호출도_관측하지_않는다() {
        Observation scheduled = Observation.createNotStarted("tasks.scheduled.execution", registry).start();

        try (Observation.Scope ignored = scheduled.openScope()) {
            Observation call = Observation.createNotStarted("http.client.requests",
                    () -> new SenderContext<>((carrier, key, value) -> { }), registry);

            assertThat(call.isNoop()).isTrue();
        } finally {
            scheduled.stop();
        }
    }

    @Test
    void actuator_요청_span은_내보내지_않는다() {
        SpanExportingPredicate predicate = new TracingConfig().backgroundSpanExportingPredicate();

        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/actuator/health")))).isFalse();
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/subscriptions")))).isTrue();
        assertThat(predicate.isExportable(spanWithTags(Map.of()))).isTrue();
    }

    @Test
    void 대기열_순번_조회_span은_내보내지_않는다() {
        SpanExportingPredicate predicate = new TracingConfig().backgroundSpanExportingPredicate();

        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/queues/{ipoId}/entries/me", "method", "GET")))).isFalse();
        // 대기열 진입, 이탈은 보낸다 (사용자 행동이 한 번씩만 일어남)
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/queues/{ipoId}/entries", "method", "POST")))).isTrue();
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/queues/{ipoId}/entries/me", "method", "DELETE")))).isTrue();
    }

    @Test
    void 외부_요청의_추적_문맥은_이어받지_않고_새_trace를_시작한다() {
        BraveBaggageManager baggageManager = new BraveBaggageManager();
        try (Tracing tracing = Tracing.newBuilder()
                .traceId128Bit(true)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .propagationFactory(new W3CPropagation(baggageManager, List.of()))
                .build()) {
            Tracer tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()), baggageManager);
            TracingConfig.NewTraceReceiverHandler handler = new TracingConfig.NewTraceReceiverHandler(tracer, new BravePropagator(tracing));
            Map<String, String> headers = Map.of("traceparent", "00-11111111111111111111111111111111-2222222222222222-01");
            ReceiverContext<Map<String, String>> context = new ReceiverContext<>((carrier, key) -> carrier.get(key), Kind.SERVER);
            context.setCarrier(headers);

            handler.onStart(context);
            Span span = handler.getRequiredSpan(context);
            span.end();

            assertThat(span.context().traceId()).isNotEqualTo("11111111111111111111111111111111");
            assertThat(span.context().parentId()).isNull();
        }
    }

    private static ObservationRegistry registry() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(context -> true)
                .observationPredicate(new TracingConfig().rootObservationPredicate());
        return registry;
    }

    private static FinishedSpan spanWithTags(Map<String, String> tags) {
        FinishedSpan span = mock(FinishedSpan.class);
        given(span.getTags()).willReturn(tags);
        return span;
    }
}
