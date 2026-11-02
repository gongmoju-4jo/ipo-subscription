package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 일시적 오류가 나면 3회 재시도(최초 1회 + 재시도 3회, 대기 1초, 2초, 4초) 후 DLT로 가는지 실제 Kafka와 PostgreSQL(Testcontainers)로 검증함.
 * 증거금 확보 요청 기록기가 항상 예외를 던지도록 대체하므로 별도 클래스로 분리함. 재시도 대기 때문에 약 7초가 걸림. Docker 필요.
 * 클래스가 끝나면 컨텍스트를 닫아서({@code @DirtiesContext}), 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속을 시도하지 않게 함.
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
@DisplayName("접수 Consumer 재시도 (Kafka, PostgreSQL)")
class SubscriptionRequestedConsumerRetryTest {

    private static final String TOPIC = SubscriptionRequestedMessage.TOPIC;
    private static final String DLT_TOPIC = TOPIC + ".DLT";

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

    @MockitoBean
    private MarginReserveRequestRecorder marginReserveRequestRecorder;

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

    /**
     * 공모주 생성 메서드가 없으므로(시드 데이터만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() throws Exception {
        // 리스너가 파티션을 모두 할당받은 뒤 시작함
        KafkaTestSupport.awaitAssignment(listenerRegistry, TOPIC);
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
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
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("일시적 오류는 3회 재시도(총 4회 시도) 후 원본 그대로 DLT로 가고, 매 시도마다 롤백되어 청약은 저장되지 않는다")
    void consume_retriesThreeTimesThenSendsToDlt_whenTransientErrorPersists() throws Exception {
        // given
        doThrow(new IllegalStateException("일시 오류")).when(marginReserveRequestRecorder).record(any());
        UUID requestId = UUID.randomUUID();
        String payload = validJson(requestId);

        // when
        long startedAt = System.nanoTime();
        kafkaTemplate.send(TOPIC, userId.toString(), payload).get(10, TimeUnit.SECONDS);

        // then
        verify(marginReserveRequestRecorder, timeout(60_000).times(4)).record(any());
        List<ConsumerRecord<String, String>> dlt = readDlt(Duration.ofSeconds(30));
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(payload);
        // 대기 1초 + 2초 + 4초가 지난 뒤에 DLT로 가야 함
        assertThat(elapsedSeconds).isGreaterThanOrEqualTo(6);
        verify(marginReserveRequestRecorder, times(4)).record(any());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ?", Integer.class, ipoId)).isZero();
    }

    private String validJson(UUID requestId) throws Exception {
        String receivedAt = OffsetDateTime.now(ZoneOffset.ofHours(9)).minusSeconds(60).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        SubscriptionRequestedMessage message = new SubscriptionRequestedMessage(UUID.randomUUID(), TOPIC,
                OffsetDateTime.now(ZoneOffset.ofHours(9)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, UUID.randomUUID(), ipoId, 100, receivedAt);
        return objectMapper.writeValueAsString(message);
    }

    /**
     * 이 테스트의 회원(메시지 키)이 보낸 DLT 메시지를 1건 읽음. 그룹에 참여하지 않고 파티션을 직접 지정함.
     */
    private List<ConsumerRecord<String, String>> readDlt(Duration timeout) {
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
            while (System.nanoTime() < deadline && found.isEmpty()) {
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
                    if (userId.toString().equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }
}
