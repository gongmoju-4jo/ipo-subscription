package com.gongmoju.common.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * 분산 추적(Zipkin)에서 요청과 관계없는 trace를 빼는 규칙을 서비스에 등록한다.
 * <p>
 * trace는 밖에서 들어온 일(HTTP 요청, Kafka 메시지 수신)에서만 시작한다. 그 밖에서 부모 없이 시작하는 관측
 * (1초 주기 스케줄러 실행, 그 안의 Kafka 발행, Eureka 갱신 같은 클라이언트 호출)은 만들지 않는다.
 * 그대로 두면 요청이 없어도 몇 초마다 trace가 쌓여 Zipkin 목록을 덮는다.
 * 관측을 만들지 않으므로 그 관측의 지표(예: {@code tasks_scheduled_execution}, 부모 없는 {@code spring_kafka_template})도 남지 않는다.
 * 대시보드, 알림이 쓰는 지표(HTTP 서버 요청, Kafka 리스너)는 수신 쪽이라 그대로 남는다.
 * 예외로 Outbox 발행({@link #OUTBOX_PUBLISH_OBSERVATION})은 주기 작업 안이지만 저장된 요청 문맥을 이어서 보내므로 만든다.
 * <p>
 * {@code /actuator/**} 요청(Prometheus 수집, 헬스체크)과 추적 문맥 없이 들어온 {@code /internal/**} 요청(게이트웨이의 주기 호출)은
 * 관측은 그대로 두고(지표 유지) span만 Zipkin으로 보내지 않는다.
 * <p>
 * Micrometer Tracing이 없는 앱에서는 등록하지 않는다. 게이트웨이는 common을 쓰지 않아 같은 규칙을 게이트웨이 안에 따로 둔다
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
public class CommonTracingAutoConfiguration {

    /**
     * Outbox 발행 관측 이름. 발행기는 주기 작업이라 부모 관측이 없지만, 저장된 요청 문맥을 이어서 보내므로 따로 허용한다
     * (common {@code TracingOutboxTraceContext})
     */
    public static final String OUTBOX_PUBLISH_OBSERVATION = "outbox.publish";

    @Bean
    public ObservationPredicate rootObservationPredicate() {
        return CommonTracingAutoConfiguration::startsFromOutside;
    }

    @Bean
    SpanExportingPredicate backgroundSpanExportingPredicate() {
        return span -> !isActuatorRequest(span) && !isBackgroundInternalRequest(span);
    }

    /**
     * 부모가 있는 관측(요청, 수신 처리 안에서 생긴 호출)은 그대로 두고, 부모가 없으면 수신(HTTP 서버 요청, Kafka 수신)일 때만 만든다.
     * 부모는 관측을 만들 때 현재 관측으로 정해지고 이 판단보다 먼저 들어간다 (Micrometer {@code Observation.createNotStarted}).
     * 이 규칙으로 만들지 않은 관측(noop)도 범위를 열면 현재 관측이 되어 부모로 잡히므로, noop 부모는 부모가 없는 것으로 본다.
     * 그러지 않으면 주기 작업 안의 Kafka 발행이 부모 span 없이 새 trace로 Zipkin에 쌓인다
     */
    static boolean startsFromOutside(String name, Observation.Context context) {
        return hasRealParent(context) || context instanceof ReceiverContext<?> || OUTBOX_PUBLISH_OBSERVATION.equals(name);
    }

    private static boolean hasRealParent(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        return parent != null && !(parent instanceof Observation observation && observation.isNoop());
    }

    static boolean isActuatorRequest(FinishedSpan span) {
        String uri = span.getTags().get("uri");
        return uri != null && uri.startsWith("/actuator");
    }

    /**
     * 추적 문맥 없이 들어온 내부 API 요청. 게이트웨이가 5초마다 부르는 청약 중 공모주 목록({@code GET /internal/ipos})이 여기에 해당한다.
     * 게이트웨이 쪽은 주기 작업이라 관측하지 않아서 문맥 없이 오고, 받는 서비스에서 새 trace가 된다.
     * 요청, 수신 처리 안에서 부른 내부 API(배정 → 청약 Feign)는 문맥을 달고 와서 부모가 있으므로 그대로 보낸다
     */
    static boolean isBackgroundInternalRequest(FinishedSpan span) {
        String uri = span.getTags().get("uri");
        String parentId = span.getParentId();
        return uri != null && uri.startsWith("/internal/") && (parentId == null || parentId.isEmpty());
    }
}
