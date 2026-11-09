package com.gongmoju.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 서비스나 테스트가 같은 타입의 빈을 직접 등록했을 때 자동 설정의 빈이 물러나는지 확인함. 컨테이너 없이 실행된다.
 */
@DisplayName("Outbox 자동 설정: 같은 타입의 빈이 있으면 물러남")
class CommonOutboxAutoConfigurationBackOffTest {

    // 자동 설정의 빈이 필요로 하는 외부 빈(Kafka, 트랜잭션, Repository)은 가짜로 채우고, 스케줄링은 끈다
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonOutboxAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(OutboxJpaRepository.class, () -> mock(OutboxJpaRepository.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withPropertyValues("outbox.publisher.enabled=false");

    @Test
    @DisplayName("같은 타입의 빈이 없으면 자동 설정이 메시지 팩토리와 발행기를 등록한다")
    void registersDefaultBeans_whenNoCustomBean() {
        // given, when, then
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OutboxMessageFactory.class);
            assertThat(context).hasSingleBean(OutboxPublisher.class);
        });
    }

    @Test
    @DisplayName("메시지 팩토리를 직접 등록하면 자동 설정의 메시지 팩토리는 등록되지 않고 직접 등록한 것만 남는다")
    void backsOff_whenCustomMessageFactoryExists() {
        // given
        OutboxMessageFactory custom = new OutboxMessageFactory(new ObjectMapper());

        // when, then
        contextRunner
                .withBean("customMessageFactory", OutboxMessageFactory.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxMessageFactory.class);
                    assertThat(context.getBean(OutboxMessageFactory.class)).isSameAs(custom);
                });
    }

    @Test
    @DisplayName("발행기를 직접 등록하면 자동 설정의 발행기는 등록되지 않고 직접 등록한 것만 남는다")
    void backsOff_whenCustomPublisherExists() {
        // given
        OutboxPublisher custom = new OutboxPublisher(
                mock(OutboxJpaRepository.class), new OutboxMessageFactory(new ObjectMapper()),
                mock(KafkaTemplate.class), mock(TransactionTemplate.class),
                new OutboxPublisherProperties(100, 5, java.time.Duration.ofSeconds(5)));

        // when, then
        contextRunner
                .withBean("customPublisher", OutboxPublisher.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxPublisher.class);
                    assertThat(context.getBean(OutboxPublisher.class)).isSameAs(custom);
                });
    }
}
