package com.gongmoju.common.outbox;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.sampler.Sampler;
import com.gongmoju.common.testapp.CommonTestApplication;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스처럼 Spring Boot와 PostgreSQL로 Outbox를 저장했을 때, 저장 리스너가 요청의 추적 문맥을 trace_parent 컬럼에 남기는지 확인함. Docker 필요.
 * 리스너는 Hibernate가 Spring 빈 생성기로 만들어야 문맥 빈을 주입받는다 (Spring Boot 기본 설정)
 */
@Testcontainers
@SpringBootTest(classes = {CommonTestApplication.class, OutboxTraceParentPersistenceTest.TracerConfig.class})
@DisplayName("Outbox 저장 시 추적 문맥 기록 (PostgreSQL)")
class OutboxTraceParentPersistenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private OutboxJpaRepository outboxRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Tracer tracer;

    @Test
    @DisplayName("요청 span 안에서 저장하면 그 traceId, spanId가 trace_parent 컬럼에 남는다")
    void save_recordsCurrentTraceParent() {
        Outbox outbox = Outbox.of("MARGIN", UUID.randomUUID(), "margin.reserved", "key-" + UUID.randomUUID(), "{}");
        Span request = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            transactionTemplate.executeWithoutResult(status -> outboxRepository.save(outbox));
        } finally {
            request.end();
        }

        assertThat(storedTraceParent(outbox.getId()))
                .isEqualTo("00-" + request.context().traceId() + "-" + request.context().spanId() + "-01");
    }

    @Test
    @DisplayName("span 밖(스케줄러 등)에서 저장하면 비워 두고, 저장은 그대로 된다")
    void save_leavesTraceParentEmptyOutsideSpan() {
        Outbox outbox = Outbox.of("IPO", UUID.randomUUID(), "ipo.closed", "key-" + UUID.randomUUID(), "{}");

        transactionTemplate.executeWithoutResult(status -> outboxRepository.save(outbox));

        assertThat(outboxRepository.findById(outbox.getId())).isPresent();
        assertThat(storedTraceParent(outbox.getId())).isNull();
    }

    private String storedTraceParent(UUID outboxId) {
        return jdbcTemplate.queryForObject("SELECT trace_parent FROM outbox WHERE outbox_id = ?", String.class, outboxId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TracerConfig {

        @Bean(destroyMethod = "close")
        Tracing tracing() {
            return Tracing.newBuilder()
                    .traceId128Bit(true)
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .currentTraceContext(ThreadLocalCurrentTraceContext.create())
                    .build();
        }

        @Bean
        Tracer tracer(Tracing tracing) {
            return new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()), new BraveBaggageManager());
        }
    }
}
