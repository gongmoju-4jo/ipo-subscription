package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 실제 스케줄러, Outbox 발행기, Kafka, PostgreSQL(Testcontainers)로 PENDING 응답 시간 초과 처리를 처음부터 끝까지 검증함. Docker 필요.
 * 응답이 없는 PENDING을 스케줄러가 찾아 인출 요청을 다시 발행하고, 응답이 오면 확정되는 흐름과, 최대 횟수에 이르면 시간 초과 보상으로
 * 반환 요청이 발행되고 반환 응답을 받으면 취소되는 흐름을 확인함.
 * 시험이 짧게 끝나도록 최대 횟수는 2, 응답 시간은 20초로 줄임. 응답 시간을 너무 짧게 하면 보상이 시작된 뒤 같은 컨텍스트의 반환 재요청 스케줄러가 반환 요청을 한 번 더 기록해 테스트가 흔들림(처리 흐름은 몇 초 안에 끝남). 처리 대상은 마지막 요청 시각을 1분 전으로 만들어 바로 걸리게 함.
 * 메시지 키를 테스트마다 다른 계좌 ID로 구분해서 같은 토픽을 공유해도 서로 영향이 없음.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=true",
        "outbox.publisher.enabled=true",
        "saga.timeout.enabled=true",
        "saga.timeout.interval=PT1S",
        "saga.timeout.response-timeout=PT20S",
        "saga.timeout.max-attempts=2"
})
@DisplayName("PENDING 응답 시간 초과 처리 (스케줄러, Kafka, PostgreSQL)")
class PendingTimeoutKafkaTest {

    private static final String RESERVE_REQUESTED = "margin.reserve-requested";
    private static final String VOID_REQUESTED = "margin.void-requested";
    private static final String RESERVED = "margin.reserved";
    private static final String VOIDED = "margin.voided";
    private static final long MARGIN_AMOUNT = 1_500_000;

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
        KafkaTestSupport.createTopicsWithDlt(kafka.getBootstrapServers(), RESERVE_REQUESTED, VOID_REQUESTED, RESERVED, VOIDED);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID ipoId;
    private UUID userId;
    private UUID accountId;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        // 응답 Consumer가 파티션을 모두 할당받은 뒤 시작함
        KafkaTestSupport.awaitAssignment(listenerRegistry, RESERVED);
        KafkaTestSupport.awaitAssignment(listenerRegistry, VOIDED);
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name IN ('subscription-margin-reserved', 'subscription-margin-voided')");
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("응답이 없는 PENDING은 인출 요청이 같은 subscriptionId, 새 eventId로 다시 발행되고, 이후 margin.reserved가 오면 CONFIRMED가 된다")
    void retriesReserveRequest_andConfirms_whenReservedArrives() throws Exception {
        // given
        insertPending(1);

        // when
        List<ConsumerRecord<String, String>> retried = readTopic(RESERVE_REQUESTED, accountId.toString(), Duration.ofSeconds(30), 1);

        // then
        assertThat(retried).hasSize(1);
        JsonNode message = objectMapper.readTree(retried.get(0).value());
        assertThat(message.get("eventType").asText()).isEqualTo(RESERVE_REQUESTED);
        assertThat(UUID.fromString(message.get("eventId").asText())).isNotNull();
        assertThat(message.get("occurredAt").asText()).endsWith("+09:00");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(message.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(message.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(message.get("ipoId").asText()).isEqualTo(ipoId.toString());
        assertThat(message.get("amount").asLong()).isEqualTo(MARGIN_AMOUNT);
        assertThat(attempts()).isEqualTo(2);
        assertThat(status()).isEqualTo("PENDING");

        // when
        kafkaTemplate.send(RESERVED, accountId.toString(), reservedJson(UUID.randomUUID())).get(10, TimeUnit.SECONDS);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CONFIRMED"));
        assertThat(readTopic(VOID_REQUESTED, accountId.toString(), Duration.ofSeconds(3), 1)).isEmpty();
    }

    @Test
    @DisplayName("최대 횟수에 이른 PENDING은 COMPENSATING(TIMEOUT)이 되어 반환 요청이 발행되고, margin.voided를 받으면 CANCELLED가 된다")
    void startsTimeoutCompensation_andCancels_whenVoidedArrives() throws Exception {
        // given
        insertPending(2);

        // when
        List<ConsumerRecord<String, String>> voidRequested = readTopic(VOID_REQUESTED, accountId.toString(), Duration.ofSeconds(30), 1);

        // then
        assertThat(voidRequested).hasSize(1);
        JsonNode message = objectMapper.readTree(voidRequested.get(0).value());
        assertThat(message.get("eventType").asText()).isEqualTo(VOID_REQUESTED);
        assertThat(message.get("occurredAt").asText()).endsWith("+09:00");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(message.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(message.get("reason").asText()).isEqualTo("TIMEOUT");
        assertThat(status()).isEqualTo("COMPENSATING");
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_reason FROM subscription WHERE subscription_id = ?", String.class, subscriptionId))
                .isEqualTo("TIMEOUT");
        assertThat(readTopic(RESERVE_REQUESTED, accountId.toString(), Duration.ofSeconds(3), 1)).isEmpty();

        // 보상이 시작된 뒤 늦게 도착한 인출 성공 응답은 무시되어야 함 (정상적으로는 오지 않는 응답). Consumer가 처리한 것(처리 기록)을 확인한 뒤 상태를 봄
        UUID lateEventId = UUID.randomUUID();
        kafkaTemplate.send(RESERVED, accountId.toString(), reservedJson(lateEventId)).get(10, TimeUnit.SECONDS);
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(processedCount(lateEventId)).isEqualTo(1));
        assertThat(status()).isEqualTo("COMPENSATING");
        assertThat(jdbcTemplate.queryForObject("SELECT margin_id FROM subscription WHERE subscription_id = ?", UUID.class, subscriptionId)).isNull();
        assertThat(outboxCount(VOID_REQUESTED)).isEqualTo(1);
        assertThat(outboxCount(RESERVE_REQUESTED)).isZero();

        // when
        kafkaTemplate.send(VOIDED, accountId.toString(), voidedJson()).get(10, TimeUnit.SECONDS);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CANCELLED"));
    }

    // 마지막 요청 시각을 1분 전으로 만들어 응답 시간(20초)이 이미 지난 PENDING을 만든다. 인출 요청 Outbox가 없어서(발행 완료로 간주) 조회 대상이 된다
    private void insertPending(int attempts) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, 'PENDING', ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, userId, accountId, MARGIN_AMOUNT, attempts,
                Timestamp.from(Instant.now().minusSeconds(60)));
    }

    private String reservedJson(UUID eventId) {
        return """
                {"eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-11-04T10:00:00.000+09:00",
                 "subscriptionId":"%s","marginId":"%s","amount":%d,"reservedAt":"2026-11-04T10:00:00.000+09:00"}"""
                .formatted(eventId, subscriptionId, UUID.randomUUID(), MARGIN_AMOUNT);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }

    private long outboxCount(String topic) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = ?", Long.class, subscriptionId, topic);
    }

    // 확보한 증거금이 없던 시간 초과 반환은 amount 0이 정상이다 (이벤트 명세 margin.void-requested 절)
    private String voidedJson() {
        return """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-11-04T10:00:00.000+09:00",
                 "subscriptionId":"%s","amount":0}""".formatted(UUID.randomUUID(), subscriptionId);
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, subscriptionId);
    }

    private int attempts() {
        return jdbcTemplate.queryForObject("SELECT attempts FROM subscription WHERE subscription_id = ?", Integer.class, subscriptionId);
    }

    /**
     * 메시지 키가 {@code key}인 메시지를 읽음. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없음.
     */
    private List<ConsumerRecord<String, String>> readTopic(String topic, String key, Duration timeout, int expectedCount) {
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
                    List<PartitionInfo> partitions = consumer.partitionsFor(topic, Duration.ofMillis(500));
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
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }
}
