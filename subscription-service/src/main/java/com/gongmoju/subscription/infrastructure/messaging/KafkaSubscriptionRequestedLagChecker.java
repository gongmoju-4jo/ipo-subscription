package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.domain.subscription.SubscriptionRequestedLagChecker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 접수 Consumer 그룹({@code subscription-requested})의 {@code subscription.requested} 랙을 AdminClient로 계산함.
 * 랙 = 파티션 끝 오프셋 - 그룹이 커밋한 오프셋. 커밋한 적이 없는 파티션은 Consumer가 처음부터 읽으므로(auto-offset-reset earliest) 맨 앞 오프셋부터 셈.
 * AdminClient는 Spring의 {@link KafkaAdmin} 설정(bootstrap-servers 등)으로 처음 조회할 때 한 번 만들어 재사용함.
 * 처음 조회할 때 만드는 이유는 마감 스케줄러를 끈 환경(테스트 등)에서 Kafka 접속 스레드를 띄우지 않기 위해서다.
 */
@Component
class KafkaSubscriptionRequestedLagChecker implements SubscriptionRequestedLagChecker, DisposableBean {

    // 조회 한 번(토픽, 커밋 오프셋, 끝 오프셋 각각)을 기다리는 최대 시간. 마감 확인 주기(5초)보다 짧게 둔다
    private static final long TIMEOUT_SECONDS = 3;

    private final KafkaAdmin kafkaAdmin;
    private AdminClient adminClient;

    KafkaSubscriptionRequestedLagChecker(KafkaAdmin kafkaAdmin) {
        this.kafkaAdmin = kafkaAdmin;
    }

    @Override
    public synchronized long currentLag() {
        if (adminClient == null) {
            adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        }
        List<TopicPartition> partitions = partitions();
        Map<TopicPartition, OffsetAndMetadata> committed = await(adminClient
                .listConsumerGroupOffsets(SubscriptionRequestedConsumer.CONSUMER_NAME)
                .partitionsToOffsetAndMetadata());
        Map<TopicPartition, Long> endOffsets = offsets(partitions, OffsetSpec.latest());

        List<TopicPartition> uncommitted = partitions.stream()
                .filter(partition -> committed.get(partition) == null)
                .toList();
        Map<TopicPartition, Long> startOffsets = uncommitted.isEmpty() ? Map.of() : offsets(uncommitted, OffsetSpec.earliest());

        long lag = 0;
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata offset = committed.get(partition);
            long position = offset != null ? offset.offset() : startOffsets.get(partition);
            lag += Math.max(0, endOffsets.get(partition) - position);
        }
        return lag;
    }

    private List<TopicPartition> partitions() {
        Map<String, TopicDescription> topics = await(adminClient
                .describeTopics(List.of(SubscriptionRequestedMessage.TOPIC))
                .allTopicNames());
        return topics.get(SubscriptionRequestedMessage.TOPIC).partitions().stream()
                .map(info -> new TopicPartition(SubscriptionRequestedMessage.TOPIC, info.partition()))
                .toList();
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
        Map<TopicPartition, OffsetSpec> request = partitions.stream()
                .collect(Collectors.toMap(Function.identity(), partition -> spec));
        Map<TopicPartition, Long> result = new HashMap<>();
        await(adminClient.listOffsets(request).all())
                .forEach((partition, info) -> result.put(partition, info.offset()));
        return result;
    }

    private <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("subscription.requested 랙 조회가 중단되었습니다.", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("subscription.requested 랙을 조회하지 못했습니다.", e);
        }
    }

    @Override
    public synchronized void destroy() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}
