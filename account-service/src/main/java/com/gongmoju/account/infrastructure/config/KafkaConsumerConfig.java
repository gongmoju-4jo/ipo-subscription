package com.gongmoju.account.infrastructure.config;

import com.gongmoju.account.global.exception.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
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

    // 블로킹 재시도라 같은 파티션(같은 계좌)의 순서가 유지된다. 재시도 횟수를 모두 소진하면 {원본 토픽}.DLT의 같은 파티션 번호로 전송한다
    // DLT 전송은 문자열 값 KafkaTemplate이라 원본 JSON이 그대로 쌓인다 (원 토픽으로 재발행하면 같은 eventId로 다시 처리됨)
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        return createErrorHandler(kafkaTemplate);
    }

    // 증거금 인출 Consumer 전용 배치 리스너 컨테이너 팩토리 (#162). 리스너가 containerFactory 이름으로 지정한다
    // Boot의 configurer로 spring.kafka.* 설정(concurrency, auto-startup 등)은 기본 팩토리와 같게 받고, 배치 리스너와 오프셋 커밋만 바꾼다
    // 에러 핸들러는 빈으로 등록하지 않는다. CommonErrorHandler 빈이 2개가 되면 Boot가 기본 팩토리에 핸들러를 넣지 않아 반환, 정산 Consumer의 재시도와 DLT 설정이 사라진다
    // 같은 값(재시도 1초, 2초, 4초, 재시도 없는 예외, .DLT)을 쓰려고 핸들러 생성 코드를 하나로 공유한다
    @Bean
    ConcurrentKafkaListenerContainerFactory<Object, Object> marginReserveBatchListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        // 배치 처리(DB 커밋)가 끝난 뒤 오프셋을 배치당 1번 커밋한다
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.setCommonErrorHandler(createErrorHandler(kafkaTemplate));
        return factory;
    }

    private DefaultErrorHandler createErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_INTERVAL_MS);
        backOff.setMultiplier(MULTIPLIER);
        backOff.setMaxInterval(MAX_INTERVAL_MS);

        // Spring Kafka 3.3의 기본 DLT 이름은 {원본 토픽}-dlt라서, 지정하지 않으면 kafka-init이 만든 .DLT 토픽과 달라 전송에 실패한다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // 다시 처리해도 결과가 같은 실패는 재시도 없이 바로 DLT로 전송한다
        // IllegalArgumentException은 도메인 생성 가드(금액 1 이상 등)의 최후 방어. 메시지 검증에 성공하면 발생하지 않는다
        handler.addNotRetryableExceptions(InvalidEventException.class, IllegalArgumentException.class);
        return handler;
    }
}
