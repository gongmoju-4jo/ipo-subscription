package com.gongmoju.gateway.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.cloud.gateway.filter.headers.observation.GatewayContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 게이트웨이의 분산 추적(Zipkin) 규칙. 서비스는 common {@code CommonTracingAutoConfiguration}이 수집 제외 규칙을 등록한다
 * (게이트웨이는 common을 쓰지 않아 따로 둔다).
 * <p>
 * <b>외부 요청의 추적 문맥은 믿지 않는다.</b> 게이트웨이는 인터넷에서 오는 요청을 받으므로, 클라이언트가 보낸
 * {@code traceparent}, {@code b3} 등을 이어받으면 아무나 traceId를 정하거나(다른 요청의 trace에 섞기) 샘플링을 켤 수 있다.
 * 그래서 외부 요청은 항상 새 trace로 시작한다 ({@link NewTraceReceiverHandler}). 클라이언트가 보낸 추적 헤더는
 * {@code RequestHeaderFilter}가 지우고, 서비스로 보낼 때 게이트웨이가 자기 문맥을 붙인다.
 * <p>
 * trace는 밖에서 들어온 HTTP 요청에서만 시작한다. 부모 없이 시작하는 관측(1초 입장 스케줄러의 Redis 명령,
 * 5초 청약 중 공모주 목록 갱신 WebClient, Eureka 갱신)은 만들지 않는다. 관측을 만들지 않으므로 그 관측의 지표도 남지 않는다.
 * <p>
 * Spring Cloud Gateway가 서비스로 보내는 호출(관측 이름 {@code http.client.requests}, 문맥 {@link GatewayContext})은 관측을 만든 뒤에
 * 부모(요청)를 넣어서, 만들 때는 부모가 비어 있을 수 있다. 그래서 문맥 타입으로 따로 허용한다.
 * 이름으로 허용하면 같은 이름을 쓰는 백그라운드 WebClient 호출(공모주 목록 갱신)까지 허용되어 타입으로 구분한다.
 * <p>
 * 관측은 그대로 두고(지표 유지) span만 Zipkin으로 보내지 않는 요청
 * <ul>
 *   <li>{@code /actuator/**}: 관리 포트의 헬스체크, Prometheus 수집</li>
 *   <li>대기열 순번 조회({@code GET /api/v1/queues/{ipoId}/entries/me}): 대기 화면이 1초 이상 간격으로 계속 부른다 (초당 최대 {@code queue.poll-budget}).
 *       대기자가 많으면 Zipkin 저장 상한을 이 요청이 채워 청약 trace가 밀려난다. 서비스를 거치지 않는 게이트웨이 단독 요청이라 자식 span이 없다</li>
 * </ul>
 * Spring Security 내부 단계는 {@code management.observations.enable.spring.security=false}로 끈다 (application.yml)
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    private static final String QUEUE_POSITION_URI = "/api/v1/queues/{ipoId}/entries/me";

    @Bean
    ObservationPredicate rootObservationPredicate() {
        return TracingConfig::startsFromOutside;
    }

    @Bean
    SpanExportingPredicate backgroundSpanExportingPredicate() {
        return span -> !isActuatorRequest(span) && !isQueuePositionPolling(span);
    }

    /**
     * Boot가 등록하는 수신 처리(요청 헤더에서 추적 문맥을 꺼내 이어 감)를 대신한다. Boot 빈과 같은 순서에 둔다.
     */
    @Bean
    @Order(MicrometerTracingAutoConfiguration.RECEIVER_TRACING_OBSERVATION_HANDLER_ORDER)
    PropagatingReceiverTracingObservationHandler<ReceiverContext<?>> propagatingReceiverTracingObservationHandler(
            Tracer tracer, Propagator propagator) {
        return new NewTraceReceiverHandler(tracer, propagator);
    }

    /**
     * 부모가 있는 관측은 그대로 두고, 부모가 없으면 수신(HTTP 서버 요청)이나 게이트웨이의 서비스 호출({@link GatewayContext})일 때만 만든다.
     * 이 규칙으로 만들지 않은 관측(noop)도 범위를 열면 현재 관측이 되어 부모로 잡히므로, noop 부모는 부모가 없는 것으로 본다
     */
    static boolean startsFromOutside(String name, Observation.Context context) {
        return hasRealParent(context)
                || context instanceof ReceiverContext<?>
                || context instanceof GatewayContext;
    }

    private static boolean hasRealParent(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        return parent != null && !(parent instanceof Observation observation && observation.isNoop());
    }

    static boolean isActuatorRequest(FinishedSpan span) {
        String uri = span.getTags().get("uri");
        return uri != null && uri.startsWith("/actuator");
    }

    static boolean isQueuePositionPolling(FinishedSpan span) {
        return QUEUE_POSITION_URI.equals(span.getTags().get("uri")) && "GET".equals(span.getTags().get("method"));
    }

    /**
     * 요청 헤더의 추적 문맥을 읽지 않고 새 trace를 시작한다. 그 밖(span 종류, 상대 주소)은 Micrometer 기본 동작과 같다.
     * 빈 헤더로 꺼내면 추적기가 부모 없는 새 span을 만들고 샘플링도 이 게이트웨이 설정으로 정한다
     */
    static class NewTraceReceiverHandler extends PropagatingReceiverTracingObservationHandler<ReceiverContext<?>> {

        private final Propagator propagator;

        NewTraceReceiverHandler(Tracer tracer, Propagator propagator) {
            super(tracer, propagator);
            this.propagator = propagator;
        }

        @Override
        public void onStart(ReceiverContext<?> context) {
            Span.Builder builder = propagator.extract(context.getCarrier(), (carrier, key) -> null);
            builder.kind(Span.Kind.valueOf(context.getKind().name()));
            if (context.getRemoteServiceName() != null) {
                builder.remoteServiceName(context.getRemoteServiceName());
            }
            getTracingContext(context).setSpan(customizeExtractedSpan(context, builder).start());
        }
    }
}
