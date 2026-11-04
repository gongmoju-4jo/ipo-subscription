package com.gongmoju.allocation.infrastructure.config;

import com.gongmoju.allocation.global.exception.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Consumer 실패 처리 설정 (이벤트 명세 3장). Boot가 {@code CommonErrorHandler} 빈을 리스너 컨테이너에 자동 적용한다.
 */
@Configuration
class KafkaConsumerConfig {

    // 재시도 3회(최초 1회 + 재시도 3회), 대기 1초 → 2초 → 4초(합계 7초)
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MS = 1_000;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 4_000;
    private static final String DLT_SUFFIX = ".DLT";

    /**
     * 블로킹 재시도라 같은 파티션의 순서가 유지된다. 재시도를 모두 소진하면 {@code {원본 토픽}.DLT}로 보낸다 (같은 파티션 번호).
     * DLT 전송용 템플릿은 문자열 값을 쓰는 기존 {@code KafkaTemplate}이라 원본 JSON이 그대로 쌓인다.
     *
     * @param kafkaTemplate DLT 전송에 사용하는 문자열 값 템플릿
     * @return 재시도, DLT 전송, 재시도 제외 예외를 설정한 에러 핸들러
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        // 토픽 이름을 직접 지정한다. Spring Kafka 3.3의 기본값은 `{원본 토픽}-dlt`라서, 지정하지 않으면 `kafka-init`이 만든 `.DLT` 토픽과 달라 전송에 실패한다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 다시 처리해도 결과가 같은 실패는 재시도 없이 바로 DLT로 보냄
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
