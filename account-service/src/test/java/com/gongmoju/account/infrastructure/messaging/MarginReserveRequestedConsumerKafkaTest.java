package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Kafka를 거쳐 증거금 인출 Consumer가 처리, 거절, DLT 전송을 하는지 실제 Kafka와 PostgreSQL(Testcontainers)로 확인한다. Docker가 필요하다
// 테스트마다 계좌가 달라서 같은 토픽을 써도 서로 영향이 없다. DLT는 메시지 키(accountId)로 구분한다
// 클래스가 끝나면 컨텍스트를 닫아서, 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속하지 않게 한다
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
class MarginReserveRequestedConsumerKafkaTest {

    private static final String TOPIC = "margin.reserve-requested";
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
    private ObjectMapper objectMapper;

    private UUID userId;
    private UUID accountId;
    private UUID ipoId;

    // 회원 생성 메서드 대신 SQL로 넣는다. 예수금 20,000원으로 시작
    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ipoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                userId, "u" + userId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 20000, 0, now(), now(), 'test', 'test')""",
                accountId, userId, UUID.randomUUID(), String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L)));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE message_key = ?", accountId.toString());
        jdbcTemplate.update("DELETE FROM ledger_entry WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM margin WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    @Test
    void 정상_메시지는_증거금을_인출하고_margin_reserved를_Outbox에_남긴다() throws Exception {
        UUID eventId = UUID.randomUUID();

        send(json(eventId, UUID.randomUUID(), 15_000));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(5_000L));
        assertThat(outboxEventTypes()).containsExactly("margin.reserved");
        assertThat(processedCount(eventId)).isEqualTo(1);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void 예수금이_부족하면_DLT가_아니라_margin_reserve_failed로_응답한다() throws Exception {
        send(json(UUID.randomUUID(), UUID.randomUUID(), 20_001));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(outboxEventTypes()).containsExactly("margin.reserve-failed"));
        assertThat(cashBalance()).isEqualTo(20_000L);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void 같은_메시지가_두_번_와도_한_번만_인출한다() throws Exception {
        String payload = json(UUID.randomUUID(), UUID.randomUUID(), 15_000);

        send(payload);
        send(payload);

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(5_000L));
        // 두 번째 메시지까지 처리될 시간을 둔 뒤에도 그대로인지 본다
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
        assertThat(cashBalance()).isEqualTo(5_000L);
        assertThat(outboxEventTypes()).containsExactly("margin.reserved");
    }

    @Test
    void JSON이_깨진_메시지는_재시도_없이_원본_그대로_DLT로_간다() throws Exception {
        String broken = "{not json";

        long startedAt = System.nanoTime();
        send(broken);

        assertArrivesInDltQuickly(broken, startedAt);
        assertThat(cashBalance()).isEqualTo(20_000L);
    }

    @Test
    void 필수_필드가_없으면_재시도_없이_원본_그대로_DLT로_간다() throws Exception {
        ObjectNode withoutUserId = (ObjectNode) objectMapper.readTree(json(UUID.randomUUID(), UUID.randomUUID(), 15_000));
        withoutUserId.remove("userId");
        String payload = withoutUserId.toString();

        long startedAt = System.nanoTime();
        send(payload);

        assertArrivesInDltQuickly(payload, startedAt);
        assertThat(cashBalance()).isEqualTo(20_000L);
    }

    @Test
    void 같은_청약인데_금액이_다르면_재시도_없이_DLT로_가고_처음_인출은_그대로다() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        send(json(UUID.randomUUID(), subscriptionId, 15_000));
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(5_000L));
        String conflicting = json(UUID.randomUUID(), subscriptionId, 14_000);

        long startedAt = System.nanoTime();
        send(conflicting);

        assertArrivesInDltQuickly(conflicting, startedAt);
        assertThat(cashBalance()).isEqualTo(5_000L);
        assertThat(outboxEventTypes()).containsExactly("margin.reserved");
    }

    private void assertArrivesInDltQuickly(String originalPayload, long startedAtNanos) {
        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(originalPayload);
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
    }

    // 청약 서비스가 보내는 모양 그대로 (이벤트 명세 2-1). 키는 accountId
    private void send(String payload) throws Exception {
        kafkaTemplate.send(TOPIC, accountId.toString(), payload).get(10, TimeUnit.SECONDS);
    }

    private String json(UUID eventId, UUID subscriptionId, long amount) throws Exception {
        return objectMapper.writeValueAsString(new MarginReserveRequestedMessage(eventId, TOPIC,
                "2026-10-27T15:59:58.410+09:00", subscriptionId, userId, accountId, ipoId, amount));
    }

    private List<ConsumerRecord<String, String>> readDlt(Duration timeout) {
        return readDlt(timeout, Integer.MAX_VALUE);
    }

    // 이 테스트의 계좌(메시지 키)로 들어온 DLT 메시지를 읽는다. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없다
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
                    if (accountId.toString().equals(record.key())) {
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

    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList("SELECT event_type FROM outbox WHERE message_key = ? ORDER BY created_at",
                String.class, accountId.toString());
    }

    private int processedCount(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
