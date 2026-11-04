package com.gongmoju.subscription.infrastructure.messaging;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

/**
 * Kafka 통합 테스트 공통 준비. 운영과 같이 토픽을 미리 만들고(운영은 {@code kafka-init}이 만듦), 리스너가 파티션을 모두 할당받은 뒤에 테스트를 시작함.
 * 토픽이 없는 상태에서 메시지를 보내면 토픽이 자동 생성되면서(브로커 기본 파티션 1개) 리스너 그룹이 재조정(rebalance)되고, 재시도 대기 중에 재조정이 일어나면 같은 메시지가 새 리스너에 다시 전달되어 시도 횟수가 늘어날 수 있음.
 */
final class KafkaTestSupport {

    // 운영 기본값 (docker/kafka/create-topics.sh). 리스너 동시 처리 수(application.yml concurrency)도 같은 값
    static final int PARTITIONS = 6;

    private KafkaTestSupport() {
    }

    /**
     * 토픽과 DLT 토픽이 없으면 만듦. 이미 있으면 건너뜀.
     */
    static void createTopicsWithDlt(String bootstrapServers, String... topics) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            Set<String> existing = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            List<NewTopic> toCreate = java.util.Arrays.stream(topics)
                    .flatMap(topic -> java.util.stream.Stream.of(topic, topic + ".DLT"))
                    .filter(name -> !existing.contains(name))
                    .map(name -> new NewTopic(name, PARTITIONS, (short) 1))
                    .toList();
            if (!toCreate.isEmpty()) {
                admin.createTopics(toCreate).all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 토픽을 구독한 리스너의 동시 처리 스레드가 모두 파티션을 할당받을 때까지 기다림.
     */
    static void awaitAssignment(KafkaListenerEndpointRegistry registry, String topic) {
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<MessageListenerContainer> containers = registry.getListenerContainers().stream()
                    .filter(container -> {
                        String[] topics = container.getContainerProperties().getTopics();
                        return topics != null && java.util.Arrays.asList(topics).contains(topic);
                    })
                    .toList();
            org.assertj.core.api.Assertions.assertThat(containers).isNotEmpty();
            for (MessageListenerContainer container : containers) {
                if (container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent) {
                    // 동시 처리 스레드(=파티션 수)마다 파티션을 1개씩 할당받아야 이후 재조정이 일어나지 않음
                    org.assertj.core.api.Assertions.assertThat(concurrent.getContainers()).hasSize(PARTITIONS);
                    for (KafkaMessageListenerContainer<?, ?> child : concurrent.getContainers()) {
                        org.assertj.core.api.Assertions.assertThat(child.getAssignedPartitions()).hasSize(1);
                    }
                } else {
                    org.assertj.core.api.Assertions.assertThat(container.getAssignedPartitions()).hasSize(PARTITIONS);
                }
            }
        });
    }
}
