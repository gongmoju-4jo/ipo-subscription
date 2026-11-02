package com.gongmoju.account.infrastructure.config;

import com.gongmoju.account.global.exception.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

// Consumer 실패 처리 설정 (이벤트 명세 3-3). 청약 서비스의 KafkaConsumerConfig와 같은 값
// Boot가 CommonErrorHandler 빈을 리스너 컨테이너에 자동 적용한다
@Configuration
class KafkaConsumerConfig {

    // 재시도 3회(최초 1회 + 재시도 3회), 대기 1초 → 2초 → 4초 (합계 7초)
    // 청약 서비스의 응답 시간 초과 기준(30초)보다 짧아야 재시도 중에 시간 초과 보상이 먼저 시작되지 않는다
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_INTERVAL_MS = 1_000;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_INTERVAL_MS = 4_000;
    private static final String DLT_SUFFIX = ".DLT";

    // 블로킹 재시도라 같은 파티션(같은 계좌)의 순서가 유지된다. 재시도를 다 쓰면 {원본 토픽}.DLT의 같은 파티션 번호로 보낸다
    // DLT 전송은 문자열 값 KafkaTemplate이라 원본 JSON이 그대로 쌓인다 (원 토픽으로 재발행하면 같은 eventId로 다시 처리됨)
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        // Spring Kafka 3.3의 기본 DLT 이름은 {원본 토픽}-dlt라서, 지정하지 않으면 kafka-init이 만든 .DLT 토픽과 달라 전송에 실패한다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 다시 처리해도 결과가 같은 실패는 재시도 없이 바로 DLT로 보낸다
        // IllegalArgumentException은 도메인 생성 가드(금액 1 이상 등)의 최후 방어. 메시지 검증을 통과하면 생기지 않는다
        handler.addNotRetryableExceptions(InvalidEventException.class, IllegalArgumentException.class);
        return handler;
    }
}
