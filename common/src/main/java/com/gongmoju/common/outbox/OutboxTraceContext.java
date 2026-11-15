package com.gongmoju.common.outbox;

import java.util.function.Supplier;

/**
 * Outbox 행에 요청의 추적 문맥을 저장하고, 발행할 때 그 문맥을 이어 붙인다.
 * <p>
 * Outbox는 요청(또는 Kafka 수신 처리) 트랜잭션에서 행만 쓰고, Kafka 발행은 1초 주기 발행기가 다른 스레드에서 한다.
 * 그대로면 요청의 trace가 Outbox에서 끊기므로, 저장할 때 W3C {@code traceparent}를 행에 같이 남기고 발행할 때 그 문맥 안에서 보낸다.
 * 발행 메시지에는 Spring Kafka가 {@code traceparent} 헤더를 붙여서, 받는 서비스가 같은 trace를 이어 간다.
 * <p>
 * 추적(Micrometer Tracing)이 없는 앱과 테스트는 {@link #NONE}을 쓴다. 저장할 문맥이 없고, 발행은 지금처럼 바로 보낸다
 */
interface OutboxTraceContext {

    OutboxTraceContext NONE = new OutboxTraceContext() {
        @Override
        public String currentTraceParent() {
            return null;
        }

        @Override
        public <T> T inStoredContext(String traceParent, String topic, Supplier<T> send) {
            return send.get();
        }
    };

    /**
     * 지금 스레드의 추적 문맥을 W3C {@code traceparent}(예: {@code 00-{traceId 32자}-{spanId 16자}-01})로 돌려준다.
     *
     * @return 문맥이 없으면 null
     */
    String currentTraceParent();

    /**
     * 저장된 문맥을 부모로 두고 발행한다. 문맥이 없거나 형식이 맞지 않으면 문맥 없이 바로 보낸다.
     *
     * @param traceParent 행에 저장된 {@code traceparent} (null 가능)
     * @param topic       발행할 토픽 (관측 태그)
     * @param send        Kafka 전송 호출
     * @return {@code send}의 결과
     */
    <T> T inStoredContext(String traceParent, String topic, Supplier<T> send);
}
