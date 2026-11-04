package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Kafka를 거쳐 배정 결과 Consumer가 정산, DLT 전송을 하는지 실제 Kafka와 PostgreSQL(Testcontainers)로 확인한다. Docker가 필요하다
// 반환 Consumer Kafka 테스트와 같은 방식: 테스트마다 계좌가 달라서 서로 영향이 없고, DLT는 메시지 키(accountId)로 구분한다
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
class AllocationResultConsumerKafkaTest {

    private static final String TOPIC = "allocation.result";
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    // 재시도(1초, 2초, 4초)를 했다면 DLT 도착까지 7초 이상 걸린다. 이보다 짧게 도착하면 재시도 없이 보낸 것
    private static final Duration NO_RETRY_LIMIT = Duration.ofSeconds(5);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "account");

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
    private MarginReserveService marginReserveService;

    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> accountIds = new ArrayList<>();
    private UUID userId;
    private UUID accountId;
    private UUID ipoId;

    // 예수금 20,000원으로 시작
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = insertAccount(userId);
        ipoId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        for (UUID id : accountIds) {
            jdbcTemplate.update("DELETE FROM outbox WHERE message_key = ?", id.toString());
            jdbcTemplate.update("DELETE FROM ledger_entry WHERE account_id = ?", id);
            jdbcTemplate.update("DELETE FROM margin WHERE account_id = ?", id);
            jdbcTemplate.update("DELETE FROM account WHERE account_id = ?", id);
        }
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", id));
    }

    @Test
    void 정상_메시지는_정산하고_margin_settled를_Outbox에_남긴다() throws Exception {
        UUID subscriptionId = reserve(15_000);
        UUID eventId = UUID.randomUUID();

        send(accountId, json(eventId, subscriptionId, accountId, "1", "2", "3", "6000"));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(14_000L));
        assertThat(marginStatus(subscriptionId)).isEqualTo("POSTED");
        assertThat(outboxEventTypes(accountId)).containsExactly("margin.reserved", "margin.settled");
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(readDlt(accountId, Duration.ofSeconds(3), Integer.MAX_VALUE)).isEmpty();
    }

    @Test
    void JSON이_깨진_메시지는_재시도_없이_원본_그대로_DLT로_간다() throws Exception {
        String broken = "{not json";

        long startedAt = System.nanoTime();
        send(accountId, broken);

        assertArrivesInDltQuickly(accountId, broken, startedAt);
        assertThat(cashBalance()).isEqualTo(20_000L);
    }

    @Test
    void 배정_대금이_소수면_버리지_않고_재시도_없이_DLT로_간다() throws Exception {
        UUID subscriptionId = reserve(15_000);
        UUID eventId = UUID.randomUUID();
        String payload = json(eventId, subscriptionId, accountId, "1", "2", "3", "6000.5");

        long startedAt = System.nanoTime();
        send(accountId, payload);

        assertArrivesInDltQuickly(accountId, payload, startedAt);
        assertThat(cashBalance()).isEqualTo(5_000L);
        assertThat(marginStatus(subscriptionId)).isEqualTo("PENDING");
        assertThat(processedCount(eventId)).isZero();
    }

    @Test
    void margin이_없으면_재시도_없이_DLT로_가고_처리_기록도_남지_않는다() throws Exception {
        UUID eventId = UUID.randomUUID();
        String payload = json(eventId, UUID.randomUUID(), accountId, "1", "2", "3", "6000");

        long startedAt = System.nanoTime();
        send(accountId, payload);

        assertArrivesInDltQuickly(accountId, payload, startedAt);
        assertThat(cashBalance()).isEqualTo(20_000L);
        assertThat(outboxEventTypes(accountId)).isEmpty();
        assertThat(processedCount(eventId)).isZero();
    }

    private void assertArrivesInDltQuickly(UUID key, String originalPayload, long startedAtNanos) {
        List<ConsumerRecord<String, String>> dlt = readDlt(key, NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(originalPayload);
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
    }

    // 인출 서비스로 PENDING margin을 만든다
    private UUID reserve(long amount) {
        UUID subscriptionId = UUID.randomUUID();
        marginReserveService.reserve(new ReserveMarginCommand(
                UUID.randomUUID(), subscriptionId, userId, accountId, ipoId, amount));
        return subscriptionId;
    }

    private UUID insertAccount(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                ownerId, "u" + ownerId.toString().substring(0, 8));
        jdbcTemplate.update("""
                        INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                        VALUES (?, ?, ?, ?, 20000, 0, now(), now(), 'test', 'test')""",
                id, ownerId, UUID.randomUUID(), String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L)));
        userIds.add(ownerId);
        accountIds.add(id);
        return id;
    }

    // 키는 accountId (명세)
    private void send(UUID key, String payload) throws Exception {
        kafkaTemplate.send(TOPIC, key.toString(), payload).get(10, TimeUnit.SECONDS);
    }

    // 배정 서비스가 보내는 모양 그대로. 숫자는 소수 같은 잘못된 값을 넣으려고 문자열로 끼워 넣는다
    private String json(UUID eventId, UUID subscriptionId, UUID accountId,
                        String equal, String proportional, String quantity, String amount) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-27T16:20:00.000+09:00","ipoId":"%s",\
                "subscriptionId":"%s","accountId":"%s","equalAllocatedQuantity":%s,"proportionalAllocatedQuantity":%s,\
                "allocatedQuantity":%s,"allocatedAmount":%s}""".formatted(
                eventId, TOPIC, ipoId, subscriptionId, accountId, equal, proportional, quantity, amount);
    }

    // 이 계좌(메시지 키)로 들어온 DLT 메시지를 읽는다. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없다
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
                    // DLT 토픽은 첫 DLT 전송 때 만들어지므로 생길 때까지 반복해서 확인한다
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

    private long cashBalance() {
        return jdbcTemplate.queryForObject("SELECT cash_balance FROM account WHERE account_id = ?", Long.class, accountId);
    }

    private String marginStatus(UUID subscriptionId) {
        return jdbcTemplate.queryForObject("SELECT status FROM margin WHERE subscription_id = ?", String.class, subscriptionId);
    }

    private List<String> outboxEventTypes(UUID key) {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox WHERE message_key = ? ORDER BY created_at",
                String.class, key.toString());
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
