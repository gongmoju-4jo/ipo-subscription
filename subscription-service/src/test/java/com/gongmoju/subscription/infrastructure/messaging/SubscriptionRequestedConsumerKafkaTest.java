package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka를 거쳐 접수 Consumer가 저장, 재전송 처리, DLT 전송을 하는지 실제 Kafka와 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * 테스트마다 회원, 종목이 달라서 같은 토픽을 공유해도 서로 영향이 없음. DLT는 메시지 키(회원 ID)로 구분함.
 * 클래스가 끝나면 컨텍스트를 닫아서({@code @DirtiesContext}), 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속을 시도하지 않게 함.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
@DisplayName("접수 Consumer (Kafka, PostgreSQL)")
class SubscriptionRequestedConsumerKafkaTest {

    private static final String TOPIC = SubscriptionRequestedMessage.TOPIC;
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

    @Autowired
    private ObjectMapper objectMapper;

    private UUID ipoId;
    private UUID userId;
    private UUID accountId;

    /**
     * 공모주 생성 메서드가 없으므로(시드 데이터만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
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
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("정상 메시지는 PENDING으로 저장하고 증거금 확보 요청을 Outbox에 기록하며 DLT로 가지 않는다")
    void consume_savesPendingAndRecordsOutbox_whenMessageIsValid() throws Exception {
        // given
        UUID requestId = UUID.randomUUID();

        // when
        send(validJson(requestId, 100));

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionCount()).isEqualTo(1));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE request_id = ?", String.class, requestId))
                .isEqualTo("PENDING");
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("같은 회원의 재전송과 이중청약 요청은 순서대로 처리되어 청약 1건, REJECTED 1건이 남고 DLT는 0건이다 (인수 기준)")
    void consume_processesResendAndDoubleSubscriptionInOrder_withoutDlt() throws Exception {
        // given
        UUID firstRequestId = UUID.randomUUID();
        UUID secondRequestId = UUID.randomUUID();

        // when
        // 키가 같아서 같은 파티션에 순서대로 들어감: 최초 요청, 같은 requestId의 재전송(eventId만 다름), 다른 requestId의 이중청약
        send(validJson(firstRequestId, 100));
        send(validJson(firstRequestId, 100));
        send(validJson(secondRequestId, 100));

        // then
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionCount()).isEqualTo(2));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE request_id = ?", String.class, firstRequestId))
                .isEqualTo("PENDING");
        Map<String, Object> rejected = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason FROM subscription WHERE request_id = ?", secondRequestId);
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("reject_reason")).isEqualTo("ALREADY_SUBSCRIBED");
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("JSON이 깨진 메시지는 재시도 없이 원본 그대로 DLT로 가고 저장하지 않는다")
    void consume_sendsToDltWithoutRetry_whenJsonIsBroken() throws Exception {
        // given
        String broken = "{not json";

        // when
        long startedAt = System.nanoTime();
        send(broken);

        // then
        assertArrivesInDltQuickly(broken, startedAt);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("subscriptionId가 userId와 requestId로 계산한 값과 다르면 재시도 없이 원본 그대로 DLT로 가고 저장하지 않는다")
    void consume_sendsToDltWithoutRetry_whenSubscriptionIdDoesNotMatch() throws Exception {
        // given
        ObjectNode tampered = (ObjectNode) objectMapper.readTree(validJson(UUID.randomUUID(), 100));
        tampered.put("subscriptionId", UUID.randomUUID().toString());
        String payload = tampered.toString();

        // when
        long startedAt = System.nanoTime();
        send(payload);

        // then
        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("필수 필드가 없으면 재시도 없이 원본 그대로 DLT로 가고 저장하지 않는다")
    void consume_sendsToDltWithoutRetry_whenRequiredFieldIsMissing() throws Exception {
        // given
        ObjectNode withoutAccountId = (ObjectNode) objectMapper.readTree(validJson(UUID.randomUUID(), 100));
        withoutAccountId.remove("accountId");
        String payload = withoutAccountId.toString();

        // when
        long startedAt = System.nanoTime();
        send(payload);

        // then
        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("같은 requestId인데 요청 내용이 다르면 재시도 없이 DLT로 가고 처음 청약은 그대로 남는다")
    void consume_sendsToDltWithoutRetry_whenSameRequestIdHasDifferentContent() throws Exception {
        // given
        UUID requestId = UUID.randomUUID();
        send(validJson(requestId, 100));
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(subscriptionCount()).isEqualTo(1));
        String conflicting = validJson(requestId, 200);

        // when
        long startedAt = System.nanoTime();
        send(conflicting);

        // then
        assertArrivesInDltQuickly(conflicting, startedAt);
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT requested_quantity FROM subscription WHERE request_id = ?", Long.class, requestId))
                .isEqualTo(100);
    }

    private void assertArrivesInDltQuickly(String originalPayload, long startedAtNanos) {
        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(originalPayload);
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
    }

    private void send(String payload) throws Exception {
        kafkaTemplate.send(TOPIC, userId.toString(), payload).get(10, TimeUnit.SECONDS);
    }

    private String validJson(UUID requestId, long requestedQuantity) throws Exception {
        String receivedAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusSeconds(60).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        SubscriptionRequestedMessage message = new SubscriptionRequestedMessage(UUID.randomUUID(), TOPIC,
                OffsetDateTime.now(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, accountId, ipoId, requestedQuantity, receivedAt);
        return objectMapper.writeValueAsString(message);
    }

    private List<ConsumerRecord<String, String>> readDlt(Duration timeout) {
        return readDlt(timeout, Integer.MAX_VALUE);
    }

    /**
     * 이 테스트의 회원(메시지 키)이 보낸 DLT 메시지를 읽음. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없음.
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
                    if (userId.toString().equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private int subscriptionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ?", Integer.class, ipoId);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox
                WHERE topic = 'margin.reserve-requested' AND aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)""",
                Integer.class, ipoId);
    }
}
