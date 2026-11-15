package com.gongmoju.common.outbox;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import com.gongmoju.common.tracing.CommonTracingAutoConfiguration;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.W3CPropagation;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.mock.MockProducerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 추적기(Brave)와 Spring Kafka 발행 관측으로, 저장한 문맥이 발행 메시지의 traceparent 헤더로 이어지는지 확인한다.
 * 서비스와 같은 수집 제외 규칙을 걸고, 발행기처럼 제외된 주기 작업(tasks.scheduled.execution) 안에서 보낸다
 */
@DisplayName("TracingOutboxTraceContext: Outbox 행의 추적 문맥 저장, 발행 연결")
class TracingOutboxTraceContextTest {

    private static final String TOPIC = "margin.reserve-requested";

    private final List<MutableSpan> finished = new CopyOnWriteArrayList<>();
    private Tracing tracing;
    private Tracer tracer;
    private ObservationRegistry registry;
    // KafkaTemplate이 보낼 때마다 Producer를 닫으므로 보낼 때마다 새로 만든다
    private final List<MockProducer<String, String>> producers = new CopyOnWriteArrayList<>();
    private KafkaTemplate<String, String> kafkaTemplate;
    private TracingOutboxTraceContext traceContext;

    @BeforeEach
    void setUp() {
        BraveBaggageManager baggageManager = new BraveBaggageManager();
        tracing = Tracing.newBuilder()
                .traceId128Bit(true)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                .propagationFactory(new W3CPropagation(baggageManager, List.of()))
                .addSpanHandler(new SpanHandler() {
                    @Override
                    public boolean end(brave.propagation.TraceContext context, MutableSpan span, Cause cause) {
                        finished.add(span);
                        return true;
                    }
                })
                .build();
        tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()), baggageManager);
        Propagator propagator = new BravePropagator(tracing);

        registry = ObservationRegistry.create();
        registry.observationConfig()
                .observationPredicate(new CommonTracingAutoConfiguration().rootObservationPredicate())
                .observationHandler(new ObservationHandler.FirstMatchingCompositeObservationHandler(
                        new PropagatingSenderTracingObservationHandler<>(tracer, propagator),
                        new PropagatingReceiverTracingObservationHandler<>(tracer, propagator),
                        new DefaultTracingObservationHandler(tracer)));

        kafkaTemplate = new KafkaTemplate<>(new MockProducerFactory<>(() -> {
            MockProducer<String, String> producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
            producers.add(producer);
            return producer;
        }));
        kafkaTemplate.setObservationEnabled(true);
        kafkaTemplate.setObservationRegistry(registry);

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tracer", tracer);
        beans.addBean("observationRegistry", registry);
        traceContext = new TracingOutboxTraceContext(beans.getBeanProvider(Tracer.class), beans.getBeanProvider(ObservationRegistry.class));
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("현재 span이 있으면 그 traceId, spanId로 W3C traceparent를 만든다")
    void currentTraceParent_formatsCurrentSpan() {
        Span span = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            assertThat(traceContext.currentTraceParent())
                    .isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
        } finally {
            span.end();
        }
    }

    @Test
    @DisplayName("현재 span이 없으면 null")
    void currentTraceParent_returnsNullWithoutSpan() {
        assertThat(traceContext.currentTraceParent()).isNull();
    }

    @Test
    @DisplayName("Tracer 빈이 없으면 저장할 문맥이 없고, 발행은 문맥 없이 바로 보낸다")
    void withoutTracer_doesNothing() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        TracingOutboxTraceContext noTracer = new TracingOutboxTraceContext(
                empty.getBeanProvider(Tracer.class), empty.getBeanProvider(ObservationRegistry.class));

        assertThat(noTracer.currentTraceParent()).isNull();
        assertThat(noTracer.inStoredContext("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", TOPIC, () -> "sent"))
                .isEqualTo("sent");
    }

    @Test
    @DisplayName("저장된 문맥으로 발행하면 메시지 헤더가 같은 trace를 잇고, 발행 span이 저장한 span의 자식이 된다")
    void inStoredContext_continuesStoredTrace() {
        // given: 요청 처리 중에 저장한 문맥
        String requestTraceId;
        String requestSpanId;
        String stored;
        Span request = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            stored = traceContext.currentTraceParent();
            requestTraceId = request.context().traceId();
            requestSpanId = request.context().spanId();
        } finally {
            request.end();
        }
        finished.clear();

        // when: 발행기처럼 제외된 주기 작업 안에서 보낸다
        inScheduledTask(() -> traceContext.inStoredContext(stored, TOPIC, () -> kafkaTemplate.send(TOPIC, "key-1", "{}")));

        // then
        String header = traceParentHeader(sent().get(0));
        assertThat(header).startsWith("00-" + requestTraceId + "-").endsWith("-01");

        MutableSpan publish = spanNamed(CommonTracingAutoConfiguration.OUTBOX_PUBLISH_OBSERVATION);
        assertThat(publish.traceId()).isEqualTo(requestTraceId);
        assertThat(publish.parentId()).isEqualTo(requestSpanId);
        MutableSpan send = finished.stream().filter(s -> s != publish).findFirst().orElseThrow();
        assertThat(send.traceId()).isEqualTo(requestTraceId);
        assertThat(send.parentId()).isEqualTo(publish.id());
        // 헤더의 부모는 Kafka 발행 span이다 (받는 쪽 수신 span이 그 자식이 된다)
        assertThat(header).contains("-" + send.id() + "-");
    }

    @Test
    @DisplayName("샘플링하지 않은 문맥은 같은 traceId로 잇되 span을 내보내지 않는다 (부하 측정 샘플링 0)")
    void inStoredContext_keepsUnsampledTraceWithoutSpans() {
        String stored = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00";

        inScheduledTask(() -> traceContext.inStoredContext(stored, TOPIC, () -> kafkaTemplate.send(TOPIC, "key-1", "{}")));

        String header = traceParentHeader(sent().get(0));
        assertThat(header).startsWith("00-0af7651916cd43dd8448eb211c80319c-").endsWith("-00");
        assertThat(finished).isEmpty();
    }

    @Test
    @DisplayName("문맥이 없거나 형식이 틀리면 문맥 없이 보낸다 (주기 작업 안이라 발행 관측도, 헤더도 없음)")
    void inStoredContext_sendsWithoutContextWhenMissingOrInvalid() {
        for (String stored : new String[]{null, "", "garbage", "00-00000000000000000000000000000000-b7ad6b7169203331-01",
                "01-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"}) {
            inScheduledTask(() -> traceContext.inStoredContext(stored, TOPIC, () -> kafkaTemplate.send(TOPIC, "key-1", "{}")));
        }

        assertThat(sent()).hasSize(5)
                .allSatisfy(record -> assertThat(traceParentHeader(record)).isNull());
        assertThat(finished).isEmpty();
    }

    private void inScheduledTask(Runnable task) {
        Observation.createNotStarted("tasks.scheduled.execution", registry).observe(task);
    }

    private List<ProducerRecord<String, String>> sent() {
        return producers.stream().flatMap(p -> p.history().stream()).toList();
    }

    private MutableSpan spanNamed(String name) {
        return finished.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
    }

    private static String traceParentHeader(ProducerRecord<String, String> record) {
        Header header = record.headers().lastHeader("traceparent");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
