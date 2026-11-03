package com.gongmoju.subscription.infrastructure.messaging;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 접수 토픽 랙 계산을 실제 Kafka(Testcontainers)로 검증함. Docker 필요.
 * 접수 Consumer는 띄우지 않고, 그룹의 커밋 오프셋을 AdminClient로 직접 바꿔서 Consumer가 처리한 상태를 만듦.
 * 테스트마다 그룹 오프셋을 토픽 끝으로 맞춰서(랙 0) 시작함.
 */
@Testcontainers
@DisplayName("subscription.requested 랙 계산 (Kafka)")
class KafkaSubscriptionRequestedLagCheckerTest {

    private static final String TOPIC = SubscriptionRequestedMessage.TOPIC;
    private static final String GROUP = SubscriptionRequestedConsumer.CONSUMER_NAME;

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    private static AdminClient admin;
    private static KafkaProducer<String, String> producer;

    private KafkaSubscriptionRequestedLagChecker lagChecker;

    @BeforeAll
    static void setUpClients() throws Exception {
        KafkaTestSupport.createTopicsWithDlt(kafka.getBootstrapServers(), TOPIC);
        admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterAll
    static void closeClients() {
        producer.close();
        admin.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        commitToEnd();
        lagChecker = new KafkaSubscriptionRequestedLagChecker(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers())));
    }

    @AfterEach
    void tearDown() {
        lagChecker.destroy();
    }

    @Test
    @DisplayName("그룹이 끝까지 처리했으면 랙은 0이다")
    void currentLag_isZero_whenCommittedToEnd() {
        // when
        long lag = lagChecker.currentLag();

        // then
        assertThat(lag).isZero();
    }

    @Test
    @DisplayName("처리하지 않은 메시지가 있으면 모든 파티션의 남은 수를 더한다")
    void currentLag_sumsUnprocessedMessagesOfAllPartitions() throws Exception {
        // given: 키를 여러 개 써서 여러 파티션에 나눠 보냄
        send(5);

        // when
        long lag = lagChecker.currentLag();

        // then
        assertThat(lag).isEqualTo(5);
    }

    @Test
    @DisplayName("보낸 메시지를 그룹이 모두 커밋하면 다시 0이 된다")
    void currentLag_returnsToZero_afterCommit() throws Exception {
        // given
        send(3);
        commitToEnd();

        // when
        long lag = lagChecker.currentLag();

        // then
        assertThat(lag).isZero();
    }

    @Test
    @DisplayName("커밋한 적이 없는 파티션은 맨 앞 오프셋부터 센다 (auto-offset-reset earliest)")
    void currentLag_countsFromEarliest_whenNoCommittedOffset() throws Exception {
        // given
        send(2);
        List<TopicPartition> partitions = partitions();
        admin.deleteConsumerGroupOffsets(GROUP, new HashSet<>(partitions)).all().get(10, TimeUnit.SECONDS);
        long retained = partitions.stream()
                .mapToLong(partition -> offsets(partitions, OffsetSpec.latest()).get(partition)
                        - offsets(partitions, OffsetSpec.earliest()).get(partition))
                .sum();

        // when
        long lag = lagChecker.currentLag();

        // then
        assertThat(lag).isEqualTo(retained).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Kafka에 접속하지 못하면 IllegalStateException")
    void currentLag_throws_whenKafkaUnreachable() {
        // given
        KafkaSubscriptionRequestedLagChecker unreachable = new KafkaSubscriptionRequestedLagChecker(
                new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1")));

        // when, then
        try {
            assertThatThrownBy(unreachable::currentLag).isInstanceOf(IllegalStateException.class);
        } finally {
            unreachable.destroy();
        }
    }

    private void send(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            producer.send(new ProducerRecord<>(TOPIC, UUID.randomUUID().toString(), "{}")).get(10, TimeUnit.SECONDS);
        }
    }

    // 그룹의 커밋 오프셋을 모든 파티션의 끝으로 맞춤 (Consumer가 모두 처리한 상태)
    private void commitToEnd() throws Exception {
        List<TopicPartition> partitions = partitions();
        Map<TopicPartition, OffsetAndMetadata> commits = offsets(partitions, OffsetSpec.latest()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new OffsetAndMetadata(entry.getValue())));
        admin.alterConsumerGroupOffsets(GROUP, commits).all().get(10, TimeUnit.SECONDS);
    }

    private List<TopicPartition> partitions() {
        return IntStream.range(0, KafkaTestSupport.PARTITIONS)
                .mapToObj(partition -> new TopicPartition(TOPIC, partition))
                .toList();
    }

    private Map<TopicPartition, Long> offsets(List<TopicPartition> partitions, OffsetSpec spec) {
        try {
            return admin.listOffsets(partitions.stream().collect(Collectors.toMap(Function.identity(), partition -> spec)))
                    .all().get(10, TimeUnit.SECONDS).entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().offset()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
