package com.gongmoju.gateway.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.junit.jupiter.api.Test;

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
        SpanExportingPredicate predicate = new TracingConfig().actuatorSpanExportingPredicate();

        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/actuator/health")))).isFalse();
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/subscriptions")))).isTrue();
        assertThat(predicate.isExportable(spanWithTags(Map.of()))).isTrue();
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
