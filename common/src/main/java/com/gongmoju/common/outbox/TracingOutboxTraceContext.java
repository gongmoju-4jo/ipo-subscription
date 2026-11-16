package com.gongmoju.common.outbox;

import com.gongmoju.common.tracing.CommonTracingAutoConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Micrometer Tracing으로 {@link OutboxTraceContext}를 구현한다. 서비스에 Micrometer Tracing이 있을 때만 등록한다 ({@link CommonOutboxAutoConfiguration}).
 * <p>
 * 발행할 때는 저장된 문맥을 현재 문맥으로 연 뒤 {@code outbox.publish} 관측을 시작하고 그 안에서 보낸다.
 * 이 관측이 부모가 되어 Spring Kafka 발행 관측이 만들어지고, 그 관측이 메시지에 {@code traceparent} 헤더를 붙인다.
 * 발행기는 주기 작업이라 부모가 없으므로, {@code outbox.publish}는 수집 제외 규칙에서 이름으로 허용한다
 * ({@link CommonTracingAutoConfiguration#OUTBOX_PUBLISH_OBSERVATION}).
 * <p>
 * 샘플링하지 않은 요청(예: 부하 측정의 샘플링 0)도 문맥은 저장한다. 그 문맥을 이어서 발행한 쪽도 샘플링하지 않아 Zipkin에는 가지 않고,
 * 로그에는 같은 traceId가 남는다
 */
class TracingOutboxTraceContext implements OutboxTraceContext {

    private static final Pattern TRACE_PARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final String INVALID_TRACE_ID = "0".repeat(32);
    private static final String INVALID_SPAN_ID = "0".repeat(16);

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<ObservationRegistry> observationRegistry;

    TracingOutboxTraceContext(ObjectProvider<Tracer> tracer, ObjectProvider<ObservationRegistry> observationRegistry) {
        this.tracer = tracer;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public String currentTraceParent() {
        Tracer current = tracer.getIfAvailable();
        if (current == null) {
            return null;
        }
        TraceContext context = current.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        String traceId = leftPad(context.traceId(), 32);
        String spanId = leftPad(context.spanId(), 16);
        if (!isValid(traceId, spanId)) {
            return null;
        }
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + traceId + "-" + spanId + "-" + flags;
    }

    @Override
    public <T> T inStoredContext(String traceParent, String topic, Supplier<T> send) {
        Tracer current = tracer.getIfAvailable();
        TraceContext parent = current == null ? null : parse(current, traceParent);
        if (parent == null) {
            return send.get();
        }
        ObservationRegistry registry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        try (CurrentTraceContext.Scope ignored = current.currentTraceContext().newScope(parent)) {
            return Observation.createNotStarted(CommonTracingAutoConfiguration.OUTBOX_PUBLISH_OBSERVATION, registry)
                    .lowCardinalityKeyValue("topic", topic)
                    .observe(send);
        }
    }

    private static TraceContext parse(Tracer tracer, String traceParent) {
        if (traceParent == null) {
            return null;
        }
        var matcher = TRACE_PARENT.matcher(traceParent);
        if (!matcher.matches() || !isValid(matcher.group(1), matcher.group(2))) {
            return null;
        }
        boolean sampled = (Integer.parseInt(matcher.group(3), 16) & 1) == 1;
        return tracer.traceContextBuilder()
                .traceId(matcher.group(1))
                .spanId(matcher.group(2))
                .sampled(sampled)
                .build();
    }

    private static boolean isValid(String traceId, String spanId) {
        return traceId.length() == 32 && spanId.length() == 16
                && !INVALID_TRACE_ID.equals(traceId) && !INVALID_SPAN_ID.equals(spanId);
    }

    // 64비트 traceId(16자)를 쓰는 추적기도 W3C 형식(32자)으로 맞춘다
    private static String leftPad(String hex, int length) {
        if (hex == null || hex.length() >= length) {
            return hex == null ? "" : hex;
        }
        return "0".repeat(length - hex.length()) + hex;
    }
}
