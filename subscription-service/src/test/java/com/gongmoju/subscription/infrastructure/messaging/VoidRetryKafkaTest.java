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
 * 실제 스케줄러, Outbox 발행기, Kafka, PostgreSQL(Testcontainers)로 반환 재요청을 처음부터 끝까지 검증함. Docker 필요.
 * 반환 응답이 오지 않는 COMPENSATING, CANCEL_REQUESTED를 스케줄러가 찾아 반환 요청을 같은 해제 사유로 다시 발행하고, 응답(margin.voided)이 오면
 * 취소되는 흐름과, 재요청 때문에 같은 응답이 한 번 더 와도 중복으로 정상 종료하는 흐름, 요청 횟수가 이전 최대 횟수(3)를 넘어도 재요청을 계속하는 것을 확인함.
 * 시험이 짧게 끝나도록 재요청 대기 시간은 20초에서 시작해 60초 상한으로 줄임(요청 횟수 1, 2, 3 이상일 때 20초, 40초, 60초). 처리 대상은 마지막 요청 시각을 5분 전으로 만들어 바로 걸리게 하고,
 * 재요청 직후 다시 걸려 메시지가 늘지 않도록 시험 시간(수 초)보다 긴 대기 시간을 둠.
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
        "saga.void-retry.initial-interval=PT20S",
        "saga.void-retry.max-interval=PT60S"
})
@DisplayName("반환 재요청 (스케줄러, Kafka, PostgreSQL)")
class VoidRetryKafkaTest {

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
     * 컨텍스트(리스너)가 시작되기 전에 토픽을 먼저 만듦 (운영은 kafka-init이 만듦). 리스너가 먼저 구독하면 브로커 기본값(파티션 1개)으로 자동 생성
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
    private UUID accountId;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        // 응답 Consumer가 파티션을 모두 할당받은 뒤 시작함
        KafkaTestSupport.awaitAssignment(listenerRegistry, RESERVED);
        KafkaTestSupport.awaitAssignment(listenerRegistry, VOIDED);
        ipoId = UUID.randomUUID();
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
    @DisplayName("응답이 없는 COMPENSATING(TIMEOUT)은 반환 요청이 같은 subscriptionId, 같은 reason, 새 eventId로 다시 발행되고, margin.voided가 오면 CANCELLED가 되며 같은 응답이 한 번 더 와도 중복으로 무시된다")
    void retriesVoidRequest_andCancels_whenVoidedArrives() throws Exception {
        // given
        insertVoiding("COMPENSATING", "TIMEOUT", 1);

        // when
        List<ConsumerRecord<String, String>> retried = readTopic(VOID_REQUESTED, accountId.toString(), Duration.ofSeconds(30), 1);

        // then
        assertThat(retried).hasSize(1);
        JsonNode message = objectMapper.readTree(retried.get(0).value());
        assertThat(message.get("eventType").asText()).isEqualTo(VOID_REQUESTED);
        assertThat(UUID.fromString(message.get("eventId").asText())).isNotNull();
        assertThat(message.get("occurredAt").asText()).endsWith("+09:00");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(message.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(message.get("reason").asText()).isEqualTo("TIMEOUT");
        assertThat(attempts()).isEqualTo(2);
        assertThat(status()).isEqualTo("COMPENSATING");
        assertThat(readTopic(RESERVE_REQUESTED, accountId.toString(), Duration.ofSeconds(3), 1)).isEmpty();

        // when
        kafkaTemplate.send(VOIDED, accountId.toString(), voidedJson(UUID.randomUUID(), 0)).get(10, TimeUnit.SECONDS);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CANCELLED"));
        assertThat(attempts()).isZero();

        // when: 재요청 때문에 계좌가 같은 내용의 응답을 한 번 더 보냄 (새 eventId)
        UUID duplicateEventId = UUID.randomUUID();
        kafkaTemplate.send(VOIDED, accountId.toString(), voidedJson(duplicateEventId, 0)).get(10, TimeUnit.SECONDS);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(processedCount(duplicateEventId)).isEqualTo(1));
        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(outboxCount(VOID_REQUESTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("응답이 없는 CANCEL_REQUESTED(USER_CANCEL)는 같은 reason으로 재요청되고, 증거금 전액의 margin.voided가 오면 CANCELLED가 된다")
    void retriesVoidRequest_forCancelRequested() throws Exception {
        // given
        insertVoiding("CANCEL_REQUESTED", "USER_CANCEL", 1);

        // when
        List<ConsumerRecord<String, String>> retried = readTopic(VOID_REQUESTED, accountId.toString(), Duration.ofSeconds(30), 1);

        // then
        assertThat(retried).hasSize(1);
        JsonNode message = objectMapper.readTree(retried.get(0).value());
        assertThat(message.get("reason").asText()).isEqualTo("USER_CANCEL");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(status()).isEqualTo("CANCEL_REQUESTED");

        // when
        kafkaTemplate.send(VOIDED, accountId.toString(), voidedJson(UUID.randomUUID(), MARGIN_AMOUNT)).get(10, TimeUnit.SECONDS);

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CANCELLED"));
    }

    @Test
    @DisplayName("요청 횟수가 이전 최대 횟수(3)를 넘어도(3) 대기 시간(60초 상한)이 지났으면 반환 요청을 계속 발행하고, 요청 횟수가 1 늘며 상태는 그대로이다")
    void keepsRetrying_whenAttemptsExceedFormerMax() throws Exception {
        // given: 요청 횟수 3의 대기 시간은 60초이고 마지막 요청이 5분 전이라 대기 시간이 지남
        insertVoiding("COMPENSATING", "CONFIRM_FAILED", 3);

        // when
        List<ConsumerRecord<String, String>> published = readTopic(VOID_REQUESTED, accountId.toString(), Duration.ofSeconds(30), 1);

        // then
        assertThat(published).hasSize(1);
        JsonNode message = objectMapper.readTree(published.get(0).value());
        assertThat(message.get("reason").asText()).isEqualTo("CONFIRM_FAILED");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(status()).isEqualTo("COMPENSATING");
        assertThat(attempts()).isEqualTo(4);
    }

    // 마지막 요청 시각을 5분 전으로 만들어 재요청 대기 시간(요청 횟수 3까지 최대 60초)이 이미 지난 청약을 만든다. 반환 요청 Outbox가 없어서(발행 완료로 간주) 조회 대상이 된다
    private void insertVoiding(String status, String cancelReason, int attempts) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId, MARGIN_AMOUNT, status, cancelReason,
                attempts, Timestamp.from(Instant.now().minusSeconds(300)));
    }

    // 확보한 증거금이 없던 시간 초과 반환은 amount 0이 정상이고, 그 외 사유는 증거금 전액이어야 한다 (이벤트 명세 margin.voided 절)
    private String voidedJson(UUID eventId, long amount) {
        return """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-11-04T10:00:00.000+09:00",
                 "subscriptionId":"%s","amount":%d}""".formatted(eventId, subscriptionId, amount);
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }

    private long outboxCount(String topic) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND topic = ?", Long.class, subscriptionId, topic);
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
