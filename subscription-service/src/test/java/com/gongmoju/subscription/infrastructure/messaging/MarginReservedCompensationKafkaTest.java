package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.SubscriptionCompensationService;
import com.gongmoju.subscription.application.subscription.SubscriptionConfirmService;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 확정 저장이 계속 실패하면 재시도 3회(최초 1회 + 재시도 3회) 후 보상 착수(청약 {@code COMPENSATING}, {@code margin.void-requested} 발행)가 일어나고,
 * 보상 착수도 실패하면 {@code margin.reserved.DLT}로 가는지 실제 Kafka와 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * Outbox 발행기를 켜서({@code outbox.publisher.enabled=true}) 기록한 해제 요청이 실제로 발행되는지까지 확인함. 확정 처리를 항상 예외를 던지도록 대체하므로 별도 클래스로 분리함. 재시도 대기 때문에 테스트마다 약 7초가 걸림.
 * 메시지 키를 테스트마다 다른 청약 ID로 보내서 같은 토픽을 공유해도 서로 영향이 없고, 결과 토픽도 이 키로 구분함.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true", "outbox.publisher.enabled=true"})
@DisplayName("margin.reserved 확정 실패 시 보상 착수 (Kafka, PostgreSQL)")
class MarginReservedCompensationKafkaTest {

    private static final String TOPIC = MarginReservedMessage.TOPIC;
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    private static final String VOID_REQUESTED_TOPIC = "margin.void-requested";
    private static final String VOIDED_TOPIC = MarginVoidedMessage.TOPIC;

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
        KafkaTestSupport.createTopicsWithDlt(kafka.getBootstrapServers(), VOID_REQUESTED_TOPIC);
        KafkaTestSupport.createTopicsWithDlt(kafka.getBootstrapServers(), VOIDED_TOPIC);
    }

    @MockitoSpyBean
    private SubscriptionConfirmService confirmService;

    @MockitoSpyBean
    private SubscriptionCompensationService compensationService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID ipoId;
    private UUID subscriptionId;
    private UUID accountId;

    /**
     * 공모주, 청약 생성 메서드가 없으므로(시드 데이터와 청약 API만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() throws Exception {
        // 리스너가 파티션을 모두 할당받은 뒤 시작함
        KafkaTestSupport.awaitAssignment(listenerRegistry, TOPIC);
        KafkaTestSupport.awaitAssignment(listenerRegistry, VOIDED_TOPIC);
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        accountId = UUID.randomUUID();
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
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), accountId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = 'subscription-margin-reserved'");
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = 'subscription-margin-voided'");
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("확정 저장이 계속 실패하면 4회 시도 후 COMPENSATING(CONFIRM_FAILED)이 되고 margin.void-requested가 발행되며 DLT는 0건이다")
    void consume_startsCompensation_whenConfirmKeepsFailing() throws Exception {
        // given
        doThrow(new IllegalStateException("일시 오류")).when(confirmService).confirm(any());

        // when
        long startedAt = System.nanoTime();
        send(validJson());

        // then
        verify(confirmService, timeout(60_000).times(4)).confirm(any());
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("COMPENSATING"));
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
        // 대기 1초 + 2초 + 4초가 지난 뒤에 보상이 시작되어야 함
        assertThat(elapsedSeconds).isGreaterThanOrEqualTo(6);
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_reason FROM subscription WHERE subscription_id = ?", String.class, subscriptionId))
                .isEqualTo("CONFIRM_FAILED");

        List<ConsumerRecord<String, String>> voidRequested = readTopic(VOID_REQUESTED_TOPIC, accountId.toString(), Duration.ofSeconds(30), 1);
        assertThat(voidRequested).hasSize(1);
        JsonNode message = objectMapper.readTree(voidRequested.get(0).value());
        assertThat(message.get("eventType").asText()).isEqualTo(VOID_REQUESTED_TOPIC);
        assertThat(message.get("eventId").asText()).isNotBlank();
        assertThat(message.get("occurredAt").asText()).endsWith("+09:00");
        assertThat(message.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(message.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(message.get("reason").asText()).isEqualTo("CONFIRM_FAILED");
        assertThat(readTopic(DLT_TOPIC, subscriptionId.toString(), Duration.ofSeconds(3), 1)).isEmpty();
    }

    @Test
    @DisplayName("보상 착수도 실패하면 원본 그대로 margin.reserved.DLT로 가고 청약은 PENDING으로 남으며 해제 요청은 발행되지 않는다")
    void consume_sendsToDlt_whenCompensationAlsoFails() throws Exception {
        // given
        doThrow(new IllegalStateException("일시 오류")).when(confirmService).confirm(any());
        doThrow(new IllegalStateException("보상도 실패")).when(compensationService).startConfirmFailedCompensation(any());
        String payload = validJson();

        // when
        send(payload);

        // then
        List<ConsumerRecord<String, String>> dlt = readTopic(DLT_TOPIC, subscriptionId.toString(), Duration.ofSeconds(60), 1);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(payload);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, subscriptionId)).isZero();
        assertThat(readTopic(VOID_REQUESTED_TOPIC, accountId.toString(), Duration.ofSeconds(3), 1)).isEmpty();
    }

    @Test
    @DisplayName("확정 실패 보상으로 해제를 요청한 뒤 margin.voided를 받으면 CANCELLED가 되고 처리 기록이 남는다")
    void consume_completesCancellation_afterCompensationAndVoidedResponse() throws Exception {
        doThrow(new IllegalStateException("확정 저장 실패")).when(confirmService).confirm(any());

        send(validJson());

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("COMPENSATING"));
        List<ConsumerRecord<String, String>> requests = readTopic(VOID_REQUESTED_TOPIC, accountId.toString(), Duration.ofSeconds(30), 1);
        assertThat(requests).hasSize(1);
        JsonNode request = objectMapper.readTree(requests.get(0).value());
        assertThat(request.get("subscriptionId").asText()).isEqualTo(subscriptionId.toString());
        assertThat(request.get("reason").asText()).isEqualTo("CONFIRM_FAILED");

        UUID voidedEventId = UUID.randomUUID();
        String voidedPayload = """
                {"eventId":"%s","eventType":"margin.voided","occurredAt":"2026-10-27T16:00:29.350+09:00",
                 "subscriptionId":"%s","amount":1500000}""".formatted(voidedEventId, subscriptionId);
        kafkaTemplate.send(VOIDED_TOPIC, accountId.toString(), voidedPayload).get(10, TimeUnit.SECONDS);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(status()).isEqualTo("CANCELLED"));
        assertThat(jdbcTemplate.queryForObject("SELECT cancel_reason FROM subscription WHERE subscription_id = ?", String.class, subscriptionId))
                .isEqualTo("CONFIRM_FAILED");
        assertThat(jdbcTemplate.queryForObject("SELECT attempts FROM subscription WHERE subscription_id = ?", Integer.class, subscriptionId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ? AND consumer_name = 'subscription-margin-voided'", Integer.class, voidedEventId))
                .isEqualTo(1);
        assertThat(readTopic(VOIDED_TOPIC + ".DLT", accountId.toString(), Duration.ofSeconds(3), 1)).isEmpty();
    }

    private void send(String payload) {
        try {
            kafkaTemplate.send(TOPIC, subscriptionId.toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String validJson() {
        return """
                {"eventId":"%s","eventType":"margin.reserved","occurredAt":"2026-10-27T15:59:58.905+09:00",
                 "subscriptionId":"%s","marginId":"%s","amount":1500000,"reservedAt":"2026-10-27T15:59:58.900+09:00"}"""
                .formatted(UUID.randomUUID(), subscriptionId, UUID.randomUUID());
    }

    private String status() {
        return jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE subscription_id = ?", String.class, subscriptionId);
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
