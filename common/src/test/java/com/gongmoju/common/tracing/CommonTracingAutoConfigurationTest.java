package com.gongmoju.common.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class CommonTracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonTracingAutoConfiguration.class));

    private final ObservationRegistry registry = registryWith(new CommonTracingAutoConfiguration().rootObservationPredicate());

    @Test
    void 부모_없는_주기_작업은_관측하지_않는다() {
        Observation scheduled = Observation.createNotStarted("tasks.scheduled.execution", registry);

        assertThat(scheduled.isNoop()).isTrue();
    }

    @Test
    void 부모_없는_클라이언트_호출은_관측하지_않는다() {
        Observation send = Observation.createNotStarted("spring.kafka.template",
                () -> new SenderContext<>((carrier, key, value) -> { }), registry);

        assertThat(send.isNoop()).isTrue();
    }

    @Test
    void 밖에서_들어온_수신은_부모가_없어도_관측한다() {
        Observation receive = Observation.createNotStarted("spring.kafka.listener",
                () -> new ReceiverContext<>((carrier, key) -> null), registry);

        assertThat(receive.isNoop()).isFalse();
    }

    @Test
    void 수신_처리_안에서_생긴_호출은_관측한다() {
        Observation receive = Observation.createNotStarted("http.server.requests",
                () -> new ReceiverContext<>((carrier, key) -> null), registry).start();

        try (Observation.Scope ignored = receive.openScope()) {
            Observation send = Observation.createNotStarted("spring.kafka.template",
                    () -> new SenderContext<>((carrier, key, value) -> { }), registry);

            assertThat(send.isNoop()).isFalse();
            assertThat(send.getContext().getParentObservation()).isSameAs(receive);
        } finally {
            receive.stop();
        }
    }

    @Test
    void 관측하지_않는_주기_작업_안의_호출도_관측하지_않는다() {
        Observation scheduled = Observation.createNotStarted("tasks.scheduled.execution", registry).start();

        try (Observation.Scope ignored = scheduled.openScope()) {
            Observation send = Observation.createNotStarted("spring.kafka.template",
                    () -> new SenderContext<>((carrier, key, value) -> { }), registry);

            assertThat(send.isNoop()).isTrue();
        } finally {
            scheduled.stop();
        }
    }

    @Test
    void actuator_요청_span은_내보내지_않는다() {
        SpanExportingPredicate predicate = new CommonTracingAutoConfiguration().backgroundSpanExportingPredicate();

        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/actuator/prometheus")))).isFalse();
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/actuator/health")))).isFalse();
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/subscriptions")))).isTrue();
        assertThat(predicate.isExportable(spanWithTags(Map.of()))).isTrue();
    }

    @Test
    void 추적_문맥_없이_들어온_내부_API_span은_내보내지_않는다() {
        SpanExportingPredicate predicate = new CommonTracingAutoConfiguration().backgroundSpanExportingPredicate();

        // 게이트웨이의 5초 주기 공모주 목록 조회 (부모 없음)
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/internal/ipos"), null))).isFalse();
        // 배정 → 청약 Feign 호출 (배정 trace의 자식)
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/internal/ipos/{ipoId}/subscriptions"), "a1b2c3d4e5f60718"))).isTrue();
        // 외부 API는 부모가 없어도 보낸다
        assertThat(predicate.isExportable(spanWithTags(Map.of("uri", "/api/v1/subscriptions"), null))).isTrue();
    }

    @Test
    void Micrometer_Tracing이_있으면_규칙을_등록한다() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ObservationPredicate.class);
            assertThat(context).hasSingleBean(SpanExportingPredicate.class);
        });
    }

    @Test
    void Micrometer_Tracing이_없으면_등록하지_않는다() {
        contextRunner.withClassLoader(new FilteredClassLoader(Tracer.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObservationPredicate.class);
                    assertThat(context).doesNotHaveBean(SpanExportingPredicate.class);
                });
    }

    private static ObservationRegistry registryWith(ObservationPredicate predicate) {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationHandler(context -> true)
                .observationPredicate(predicate);
        return registry;
    }

    private static FinishedSpan spanWithTags(Map<String, String> tags) {
        return spanWithTags(tags, null);
    }

    private static FinishedSpan spanWithTags(Map<String, String> tags, String parentId) {
        FinishedSpan span = mock(FinishedSpan.class);
        given(span.getTags()).willReturn(tags);
        given(span.getParentId()).willReturn(parentId);
        return span;
    }
}
