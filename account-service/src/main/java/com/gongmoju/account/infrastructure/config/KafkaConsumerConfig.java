package com.gongmoju.account.infrastructure.config;

import com.gongmoju.account.global.exception.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class KafkaConsumerConfig {

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

    // 배치 리스너용 공용 컨테이너 팩토리 (#114). 리스너에 containerFactory = BATCH_LISTENER_FACTORY로 지정한다
    // 동시 처리 수, 에러 핸들러(위 kafkaErrorHandler) 등은 Boot 기본 팩토리와 동일하게 적용되고, 배치 수신만 다르다
    // batch size(max.poll.records)는 리스너마다 다르게 지정한다 (@KafkaListener properties)
    // 배치 중 1건 실패는 리스너가 BatchListenerFailedException(실패 위치)으로 알리면, 에러 핸들러가 앞 건은 완료 처리하고 실패 건만 재시도, DLT 처리한다
    public static final String BATCH_LISTENER_FACTORY = "batchKafkaListenerContainerFactory";

    @Bean(BATCH_LISTENER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> batchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        // 리스너 메서드가 정상 종료하면(배치 트랜잭션 커밋 후) 배치 전체 오프셋을 커밋한다
        // application.yml의 ack-mode: record는 1건 리스너용이라 배치 팩토리에서는 BATCH로 변경한다
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
