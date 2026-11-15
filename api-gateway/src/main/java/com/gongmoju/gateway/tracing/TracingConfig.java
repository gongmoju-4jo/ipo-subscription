package com.gongmoju.gateway.tracing;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationView;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 분산 추적(Zipkin)에서 요청과 관계없는 trace를 빼는 규칙. 서비스는 common {@code CommonTracingAutoConfiguration}이 같은 규칙을 등록한다
 * (게이트웨이는 common을 쓰지 않아 따로 둔다).
 * <p>
 * trace는 밖에서 들어온 HTTP 요청에서만 시작한다. 부모 없이 시작하는 관측(1초 입장 스케줄러의 Redis 명령,
 * 5초 청약 중 공모주 목록 갱신 WebClient, Eureka 갱신)은 만들지 않는다. 관측을 만들지 않으므로 그 관측의 지표도 남지 않는다.
 * <p>
 * Spring Cloud Gateway가 서비스로 보내는 호출({@code spring.cloud.gateway.*})은 관측을 만든 뒤에 부모(요청)를 넣어서,
 * 만들 때는 부모가 비어 있을 수 있다. 그래서 이름으로 따로 허용한다.
 * <p>
 * {@code /actuator/**} 요청(관리 포트의 헬스체크, Prometheus 수집)은 관측은 그대로 두고(지표 유지) span만 Zipkin으로 보내지 않는다.
 * Spring Security 내부 단계는 {@code management.observations.enable.spring.security=false}로 끈다 (application.yml)
 */
@Configuration(proxyBeanMethods = false)
class TracingConfig {

    private static final String GATEWAY_OBSERVATION_PREFIX = "spring.cloud.gateway.";

    @Bean
    ObservationPredicate rootObservationPredicate() {
        return TracingConfig::startsFromOutside;
    }

    @Bean
    SpanExportingPredicate actuatorSpanExportingPredicate() {
        return span -> !isActuatorRequest(span);
    }

    /**
     * 부모가 있는 관측은 그대로 두고, 부모가 없으면 수신(HTTP 서버 요청)이나 게이트웨이의 서비스 호출일 때만 만든다.
     * 이 규칙으로 만들지 않은 관측(noop)도 범위를 열면 현재 관측이 되어 부모로 잡히므로, noop 부모는 부모가 없는 것으로 본다
     */
    static boolean startsFromOutside(String name, Observation.Context context) {
        return hasRealParent(context)
                || context instanceof ReceiverContext<?>
                || name.startsWith(GATEWAY_OBSERVATION_PREFIX);
    }

    private static boolean hasRealParent(Observation.Context context) {
        ObservationView parent = context.getParentObservation();
        return parent != null && !(parent instanceof Observation observation && observation.isNoop());
    }

    static boolean isActuatorRequest(FinishedSpan span) {
        String uri = span.getTags().get("uri");
        return uri != null && uri.startsWith("/actuator");
    }
}
