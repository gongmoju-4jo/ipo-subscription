package com.gongmoju.subscription.infrastructure.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka를 거쳐 {@code margin.reserved} Consumer가 청약을 확정하고, 중복 응답을 무시하고, 잘못된 메시지를 DLT로 보내는지 실제 Kafka와 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * 메시지 키를 테스트마다 다른 청약 ID로 보내서 같은 토픽을 공유해도 서로 영향이 없고, DLT도 이 키로 구분함.
 * 클래스가 끝나면 컨텍스트를 닫아서({@code @DirtiesContext}), 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속을 시도하지 않게 함.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
@DisplayName("margin.reserved Consumer (Kafka, PostgreSQL)")
class MarginReservedConsumerKafkaTest {

    private static final String TOPIC = MarginReservedMessage.TOPIC;
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    // 재시도(1초, 2초, 4초)가 일어났다면 DLT 도착까지 7초 이상 걸리므로, 이보다 충분히 짧은 시간 안에 도착하면 재시도 없이 보낸 것임
    private static final Duration NO_RETRY_LIMIT = Duration.ofSeconds(5);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.0");

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, 'PENDING', 1, now(), now() - interval '1 minute',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = 'subscription-margin-reserved'");
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("정상 메시지는 PENDING 청약을 CONFIRMED로 바꾸고 증거금 ID, 확정 시각을 저장하며 DLT로 가지 않는다")
    void consume_confirmsSubscription_whenMessageIsValid() {
        // given
        UUID marginId = UUID.randomUUID();

        // when
        send(validJson(UUID.randomUUID(), marginId));

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CONFIRMED"));
        assertThat(jdbcTemplate.queryForObject("SELECT margin_id FROM subscription WHERE subscription_id = ?", UUID.class, subscriptionId))
                .isEqualTo(marginId);
        assertThat(jdbcTemplate.queryForObject("SELECT confirmed_at IS NOT NULL FROM subscription WHERE subscription_id = ?", Boolean.class, subscriptionId))
                .isTrue();
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("같은 메시지가 두 번 오면 한 번만 확정하고 처리 기록은 1개이며 DLT는 0건이다")
    void consume_confirmsOnce_whenSameMessageArrivesTwice() {
        // given
        String payload = validJson(UUID.randomUUID(), UUID.randomUUID());

        // when
        send(payload);
        send(payload);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CONFIRMED"));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("SELECT version FROM subscription WHERE subscription_id = ?", Long.class, subscriptionId))
                        .isEqualTo(1L));
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 subscriptionId는 재시도 없이 바로 DLT로 가고 원본 JSON이 그대로 쌓인다")
    void consume_sendsToDltWithoutRetry_whenSubscriptionNotFound() {
        // given
        UUID unknownSubscriptionId = UUID.randomUUID();
        String payload = validJson(unknownSubscriptionId, UUID.randomUUID(), UUID.randomUUID());

        // when
        long startedAt = System.nanoTime();
        kafkaTemplate.send(TOPIC, unknownSubscriptionId.toString(), payload);

        // then
        assertArrivesInDltQuickly(unknownSubscriptionId, payload, startedAt);
        assertThat(status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("깨진 JSON은 재시도 없이 바로 DLT로 가고 청약은 PENDING으로 남는다")
    void consume_sendsToDltWithoutRetry_whenJsonIsBroken() {
        // given
        String broken = "{깨진 json";

        // when
        long startedAt = System.nanoTime();
        send(broken);

        // then
        assertArrivesInDltQuickly(subscriptionId, broken, startedAt);
        assertThat(status()).isEqualTo("PENDING");
    }

    private void assertArrivesInDltQuickly(UUID key, String originalPayload, long startedAtNanos) {
        List<ConsumerRecord<String, String>> dlt = readDlt(key, NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(originalPayload);
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
    }

    private void send(String payload) {
        try {
            kafkaTemplate.send(TOPIC, subscriptionId.toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String validJson(UUID eventId, UUID marginId) {
        return validJson(subscriptionId, marginId, eventId);
    }

    private String validJson(UUID targetSubscriptionId, UUID marginId, UUID eventId) {
        return """
                {"eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-10-27T15:59:58.905+09:00",
                 "subscriptionId":"%s","marginId":"%s","amount":1500000,"reservedAt":"2026-10-27T15:59:58.900+09:00"}"""
                .formatted(eventId, targetSubscriptionId, marginId);
    }

    private List<ConsumerRecord<String, String>> readDlt(Duration timeout) {
        return readDlt(subscriptionId, timeout, Integer.MAX_VALUE);
    }

    /**
     * 메시지 키가 {@code key}인 DLT 메시지를 읽음. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없음.
     */
    private List<ConsumerRecord<String, String>> readDlt(UUID key, Duration timeout, int expectedCount) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            boolean assigned = false;
            while (System.nanoTime() < deadline && found.size() < expectedCount) {
                if (!assigned) {
                    // DLT 토픽은 첫 DLT 전송 때 만들어지므로 생길 때까지 반복해서 확인함
                    List<PartitionInfo> partitions = consumer.partitionsFor(DLT_TOPIC, Duration.ofMillis(500));
                    if (partitions != null && !partitions.isEmpty()) {
                        List<TopicPartition> topicPartitions = partitions.stream()
                                .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
                        consumer.assign(topicPartitions);
                        consumer.seekToBeginning(topicPartitions);
                        assigned = true;
                    }
                    continue;
                }
                consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                    if (key.toString().equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, subscriptionId);
    }
}
