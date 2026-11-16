package com.gongmoju.common.outbox;

import jakarta.persistence.PrePersist;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Outbox 행을 저장하기 직전에 지금 스레드의 추적 문맥({@code traceparent})을 행에 남긴다.
 * 서비스는 지금처럼 {@link Outbox#of}로 만들어 저장하면 된다.
 * <p>
 * Spring Boot가 Hibernate에 Spring 빈 생성기를 넣어 주므로 이 리스너는 Spring이 만들고 {@link OutboxTraceContext}를 주입받는다.
 * Spring 없이 Hibernate가 직접 만들면(기본 생성자) 문맥을 남기지 않는다
 */
class OutboxTraceParentListener {

    private final ObjectProvider<OutboxTraceContext> traceContext;

    OutboxTraceParentListener() {
        this.traceContext = null;
    }

    @Autowired
    OutboxTraceParentListener(ObjectProvider<OutboxTraceContext> traceContext) {
        this.traceContext = traceContext;
    }

    @PrePersist
    void recordTraceParent(Outbox outbox) {
        if (traceContext == null) {
            return;
        }
        OutboxTraceContext context = traceContext.getIfAvailable(() -> OutboxTraceContext.NONE);
        outbox.recordTraceParent(context.currentTraceParent());
    }
}
