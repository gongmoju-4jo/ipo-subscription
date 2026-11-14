package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginReserveService;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
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

// 증거금 인출 Consumer의 배치 처리(#162)를 실제 Kafka와 PostgreSQL(Testcontainers)로 확인한다. Docker가 필요하다
// 메시지를 먼저 쌓아 두고 리스너를 시작해서, 쌓인 메시지가 한 번에(배치로) 전달되게 한다
// 실패는 DB 트리거로 만든다 (margin INSERT가 특정 금액에서 예외). 금액 77,777원은 배치 처리에서만 한 번 실패, 88,888원은 항상 실패
// 클래스가 끝나면 컨텍스트를 닫아서, 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속하지 않게 한다
@DisplayName("증거금 인출 Consumer 배치 처리 (Kafka)")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=false"})
class MarginReserveBatchConsumerKafkaTest {

    private static final String TOPIC = MarginReserveService.TOPIC;
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    private static final long START_CASH = 1_000_000L;
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

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    private UUID userId;
    private UUID accountId;
    private UUID ipoId;

    @BeforeEach
    void setUp() {
        // 앞 테스트에서 시작한 리스너를 멈춘다. 이 테스트가 메시지를 다 보낸 뒤에 시작해야 쌓인 메시지가 배치로 전달된다
        MessageListenerContainer container = listener();
        if (container.isRunning()) {
            container.stop();
        }

        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        ipoId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (user_id, login_id, password_hash, name, role, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'hash', '테스트', 'USER', now(), now(), 'test', 'test')""",
                userId, "u" + userId.toString().substring(0, 8));
        jdbcTemplate.update("""
                INSERT INTO account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 0, now(), now(), 'test', 'test')""",
                accountId, userId, UUID.randomUUID(),
                String.valueOf(ThreadLocalRandom.current().nextLong(100_000_000_000L, 1_000_000_000_000L)), START_CASH);

        // margin INSERT 실패 장치: 77,777원은 시퀀스 첫 호출에서만(배치 처리에서 한 번), 88,888원은 항상 실패한다
        jdbcTemplate.execute("DROP SEQUENCE IF EXISTS test_fail_once_seq");
        jdbcTemplate.execute("CREATE SEQUENCE test_fail_once_seq");
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_fail_margin() RETURNS trigger AS $$
                BEGIN
                    IF NEW.amount = 88888 THEN
                        RAISE EXCEPTION 'test: always fail';
                    END IF;
                    IF NEW.amount = 77777 AND nextval('test_fail_once_seq') = 1 THEN
                        RAISE EXCEPTION 'test: fail once';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_margin_trigger ON margin");
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_margin_trigger BEFORE INSERT ON margin
                FOR EACH ROW EXECUTE FUNCTION test_fail_margin()""");
    }

    @AfterEach
    void tearDown() {
        MessageListenerContainer container = listener();
        if (container.isRunning()) {
            container.stop();
        }
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_margin_trigger ON margin");
        jdbcTemplate.update("DELETE FROM outbox WHERE message_key = ?", accountId.toString());
        jdbcTemplate.update("DELETE FROM ledger_entry WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM margin WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM account WHERE account_id = ?", accountId);
        jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", userId);
    }

    @Test
    @DisplayName("여러 건을 쌓아 두고 리스너를 시작하면 한 번에 묶어서 처리하고 모두 한 번씩 반영한다")
    void consume_accumulatedMessages_processesInBatches() throws Exception {
        // given: 100원짜리 20건을 쌓아 둔다
        long batchesBefore = batchSize().count();
        double messagesBefore = batchSize().totalAmount();
        for (int i = 0; i < 20; i++) {
            send(json(UUID.randomUUID(), UUID.randomUUID(), 100));
        }

        // when
        listener().start();

        // then
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 2_000));
        assertThat(count("margin WHERE account_id = '" + accountId + "'")).isEqualTo(20);
        assertThat(count("ledger_entry WHERE account_id = '" + accountId + "'")).isEqualTo(20);
        // 배치가 실제로 적용됐는지: 처리한 메시지 수(20)가 배치 호출 횟수보다 많다 (배치마다 평균 2건 이상)
        double messages = batchSize().totalAmount() - messagesBefore;
        long batches = batchSize().count() - batchesBefore;
        assertThat(messages).isEqualTo(20);
        assertThat(batches).isLessThan(20);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("정상 메시지 사이에 JSON이 깨진 메시지가 있으면 그 건만 재시도 없이 DLT로 가고 앞뒤 정상 건은 한 번씩 반영한다")
    void consume_malformedMessageBetweenValid_deadLettersOnlyMalformed() throws Exception {
        // given
        UUID firstEventId = UUID.randomUUID();
        UUID lastEventId = UUID.randomUUID();
        String broken = "{not json";
        send(json(firstEventId, UUID.randomUUID(), 5_000));
        send(broken);
        send(json(lastEventId, UUID.randomUUID(), 3_000));

        // when
        listener().start();

        // then
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 8_000));
        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(broken);
        assertThat(processedCount(firstEventId)).isEqualTo(1);
        assertThat(processedCount(lastEventId)).isEqualTo(1);
        assertThat(outboxEventTypes()).containsExactly("margin.reserved", "margin.reserved");
    }

    @Test
    @DisplayName("정상 메시지 사이에 같은 청약인데 금액이 다른 재요청이 있으면 그 건만 바로 DLT로 가고 나머지는 한 번씩 반영한다")
    void consume_conflictingResendBetweenValid_deadLettersOnlyConflicting() throws Exception {
        // given
        UUID subscriptionId = UUID.randomUUID();
        UUID lastEventId = UUID.randomUUID();
        send(json(UUID.randomUUID(), subscriptionId, 5_000));
        String conflicting = json(UUID.randomUUID(), subscriptionId, 4_000);
        send(conflicting);
        send(json(lastEventId, UUID.randomUUID(), 3_000));

        // when
        listener().start();

        // then: 처음 인출이 반영된 시점부터 5초 안에 DLT에 도착해야 한다 (재시도했다면 7초 이상)
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isLessThan(START_CASH));
        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(conflicting);
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 8_000));
        assertThat(processedCount(lastEventId)).isEqualTo(1);
        assertThat(outboxEventTypes()).containsExactly("margin.reserved", "margin.reserved");
    }

    @Test
    @DisplayName("한 건이 배치 처리에서만 한 번 실패하면 건별 재처리로 DLT 없이 전부 반영한다")
    void consume_failsOnlyInBatch_fallsBackAndAppliesAllWithoutDlt() throws Exception {
        // given: 77,777원 요청은 배치 처리 때만 margin INSERT가 실패한다
        double fallbackBefore = meterRegistry.counter("margin.reserve.batch.fallback").count();
        send(json(UUID.randomUUID(), UUID.randomUUID(), 5_000));
        send(json(UUID.randomUUID(), UUID.randomUUID(), 77_777));
        send(json(UUID.randomUUID(), UUID.randomUUID(), 3_000));

        // when
        listener().start();

        // then
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 85_777));
        assertThat(meterRegistry.counter("margin.reserve.batch.fallback").count()).isGreaterThan(fallbackBefore);
        assertThat(count("margin WHERE account_id = '" + accountId + "'")).isEqualTo(3);
        assertThat(readDlt(Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("한 건이 계속 실패하면 그 건만 재시도 뒤 DLT로 가고 도착까지 30초 미만이며 뒤 건은 그 뒤에 반영한다")
    void consume_alwaysFailingMessage_deadLettersOnlyThatAfterRetries() throws Exception {
        // given: 88,888원 요청은 항상 margin INSERT가 실패한다
        String failing = json(UUID.randomUUID(), UUID.randomUUID(), 88_888);
        send(json(UUID.randomUUID(), UUID.randomUUID(), 5_000));
        send(failing);
        send(json(UUID.randomUUID(), UUID.randomUUID(), 3_000));

        // when
        listener().start();

        // then
        // 앞 건은 배치 실패 뒤 건별 재처리에서 바로 반영된다. 이 시점이 실패한 건의 첫 실패와 거의 같다
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 5_000));
        long firstFailedAt = System.nanoTime();
        // 뒤 건은 실패한 건의 재시도(1초, 2초, 4초, 합계 7초)가 끝날 때까지 반영되지 않는다 (같은 파티션의 순서 유지)
        await().during(5, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 5_000));
        // 재시도가 끝나면 실패한 건만 DLT로 간다. 첫 실패부터 30초 미만
        List<ConsumerRecord<String, String>> dlt = readDlt(Duration.ofSeconds(24), 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstFailedAt);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(failing);
        assertThat(elapsedMillis).isLessThan(30_000);
        // 그 뒤에 뒤 건이 반영된다
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 8_000));
        assertThat(count("margin WHERE account_id = '" + accountId + "'")).isEqualTo(2);
    }

    @Test
    @DisplayName("배치를 커밋한 뒤 같은 메시지를 다시 보내도 잔고는 변하지 않는다")
    void consume_sameMessagesAfterBatchCommit_doesNotChangeBalance() throws Exception {
        // given: 배치로 처리해서 커밋한다
        String first = json(UUID.randomUUID(), UUID.randomUUID(), 5_000);
        String second = json(UUID.randomUUID(), UUID.randomUUID(), 3_000);
        send(first);
        send(second);
        listener().start();
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> assertThat(cashBalance()).isEqualTo(START_CASH - 8_000));

        // when: 같은 메시지를 다시 보낸다
        send(first);
        send(second);

        // then: 처리될 시간을 둔 뒤에도 그대로다
        assertThat(readDlt(Duration.ofSeconds(4))).isEmpty();
        assertThat(cashBalance()).isEqualTo(START_CASH - 8_000);
        assertThat(outboxEventTypes()).containsExactly("margin.reserved", "margin.reserved");
    }

    private MessageListenerContainer listener() {
        return listenerRegistry.getListenerContainer(MarginReserveService.CONSUMER_NAME);
    }

    private DistributionSummary batchSize() {
        return meterRegistry.get("margin.reserve.batch.size").summary();
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

    // 표 이름 뒤에 WHERE 조건을 붙여 쓸 수 있다
    private long count(String tableAndCondition) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + tableAndCondition, Long.class);
    }
}
