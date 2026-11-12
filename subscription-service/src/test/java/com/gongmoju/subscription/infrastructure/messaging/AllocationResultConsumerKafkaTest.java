package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.application.subscription.SubscriptionAllocateService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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
 * Kafka를 거쳐 {@code allocation.result} Consumer가 청약을 {@code ALLOCATED}로 바꾸고, 중복 결과를 무시하고, 잘못된 메시지를 재시도 없이 DLT로 보내는지
 * 실제 Kafka와 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * 메시지 키를 테스트마다 다른 계좌 ID(토픽 키와 같은 값)로 보내서 같은 토픽을 공유해도 서로 영향이 없고, DLT도 이 키로 구분함.
 * 클래스가 끝나면 컨텍스트를 닫아서({@code @DirtiesContext}), 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속을 시도하지 않게 함.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
@DisplayName("allocation.result Consumer (Kafka, PostgreSQL)")
class AllocationResultConsumerKafkaTest {

    private static final String TOPIC = AllocationResultMessage.TOPIC;
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

    /**
     * 컨텍스트(리스너)가 시작되기 전에 토픽을 먼저 만듦 (운영은 kafka-init이 만듦). 리스너가 먼저 구독하면 브로커 기본값(파티션 1개)으로 자동 생성되기 때문.
     */
    @BeforeAll
    static void createTopics() throws Exception {
        KafkaTestSupport.createTopicsWithDlt(kafka.getBootstrapServers(), TOPIC);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID accountId;
    private UUID subscriptionId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함. 마감된 공모주의 확정({@code CONFIRMED}) 청약 100주로 시작함.
     */
    @BeforeEach
    void setUp() {
        // 리스너가 파티션을 모두 할당받은 뒤 시작함
        KafkaTestSupport.awaitAssignment(listenerRegistry, TOPIC);
        ipoId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '2 day', now() - interval '1 day', current_date + 5, 'CLOSED', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, margin_id, attempts, received_at, confirmed_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, 'CONFIRMED', ?, 0, now() - interval '2 day', now() - interval '2 day',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionAllocateService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("정상 메시지는 CONFIRMED 청약을 ALLOCATED로 바꾸고 배정 수량, 배정 대금을 저장하며 DLT로 가지 않는다")
    void consume_allocatesSubscription_whenMessageIsValid() {
        // given

        // when
        send(validJson(UUID.randomUUID(), 1, 2, 3, 45_000));

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("ALLOCATED"));
        assertThat(jdbcTemplate.queryForMap("SELECT allocated_quantity, allocated_amount FROM subscription WHERE subscription_id = ?", subscriptionId))
                .containsEntry("allocated_quantity", 3L)
                .containsEntry("allocated_amount", 45_000L);
        assertThat(readDlt(Duration.ofSeconds(3), Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("같은 메시지가 두 번 오면 한 번만 배정하고 DLT는 0건이다")
    void consume_allocatesOnce_whenSameMessageArrivesTwice() {
        // given
        String payload = validJson(UUID.randomUUID(), 1, 2, 3, 45_000);

        // when
        send(payload);
        send(payload);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("ALLOCATED"));
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE consumer_name = ?",
                        Integer.class, SubscriptionAllocateService.CONSUMER_NAME)).isEqualTo(1));
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM subscription WHERE subscription_id = ?", Long.class, subscriptionId))
                .isEqualTo(1L);
        assertThat(readDlt(Duration.ofSeconds(3), Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    @DisplayName("균등 + 비례가 최종 배정 수량과 다른 메시지는 재시도 없이 바로 DLT로 가고 원본 JSON이 그대로 쌓이며 청약은 CONFIRMED로 남는다")
    void consume_sendsToDltWithoutRetry_whenQuantitiesDoNotAddUp() {
        // given
        String payload = validJson(UUID.randomUUID(), 1, 1, 3, 45_000);

        // when
        long startedAt = System.nanoTime();
        send(payload);

        // then
        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(status()).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("배정 대금이 배정 수량 × 공모가와 다르면 재시도 없이 바로 DLT로 가고 청약은 CONFIRMED로 남으며 처리 기록도 남지 않는다")
    void consume_sendsToDltWithoutRetry_whenAmountDiffersFromOfferPrice() {
        // given
        UUID eventId = UUID.randomUUID();
        String payload = validJson(eventId, 1, 2, 3, 45_001);

        // when
        long startedAt = System.nanoTime();
        send(payload);

        // then
        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(status()).isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(value = {
            "PENDING, NULL",
            "REJECTED, NULL",
            "COMPENSATING, CONFIRM_FAILED",
            "CANCEL_REQUESTED, USER_CANCEL",
            "CANCELLED, TIMEOUT"
    }, nullValues = "NULL")
    @DisplayName("CONFIRMED, ALLOCATED, REFUNDED 외 상태의 청약이면 재시도 없이 바로 DLT로 가고 원본 JSON이 그대로 쌓이며, 상태는 그대로이고 처리 기록도 남지 않는다")
    void consume_sendsToDltWithoutRetry_whenStatusIsUnexpected(String status, String cancelReason) {
        // given
        String rejectReason = "REJECTED".equals(status) ? "INSUFFICIENT_BALANCE" : null;
        jdbcTemplate.update("UPDATE subscription SET status = ?, cancel_reason = ?, reject_reason = ? WHERE subscription_id = ?",
                status, cancelReason, rejectReason, subscriptionId);
        UUID eventId = UUID.randomUUID();
        String payload = validJson(eventId, 1, 2, 3, 45_000);

        // when
        long startedAt = System.nanoTime();
        send(payload);

        // then
        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(status()).isEqualTo(status);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId)).isZero();
    }

    private void assertArrivesInDltQuickly(String originalPayload, long startedAtNanos) {
        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(originalPayload);
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
    }

    // 토픽 키는 accountId (이벤트 명세)
    private void send(String payload) {
        try {
            kafkaTemplate.send(TOPIC, accountId.toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String validJson(UUID eventId, long equal, long proportional, long allocated, long amount) {
        return """
                {"eventId":"%s","eventType":"allocation.result","occurredAt":"2026-10-27T16:20:00.000+09:00",
                 "ipoId":"%s","subscriptionId":"%s","accountId":"%s","equalAllocatedQuantity":%d,
                 "proportionalAllocatedQuantity":%d,"allocatedQuantity":%d,"allocatedAmount":%d}"""
                .formatted(eventId, ipoId, subscriptionId, accountId, equal, proportional, allocated, amount);
    }

    /**
     * 메시지 키가 이 테스트의 계좌 ID인 DLT 메시지를 읽음. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없음.
     */
    private List<ConsumerRecord<String, String>> readDlt(Duration timeout, int expectedCount) {
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
                    if (accountId.toString().equals(record.key())) {
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
