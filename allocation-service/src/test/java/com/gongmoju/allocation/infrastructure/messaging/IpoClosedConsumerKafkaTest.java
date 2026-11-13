package com.gongmoju.allocation.infrastructure.messaging;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscriptionReader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;

// Kafka를 거쳐 ipo.closed Consumer가 배정을 실행하고, 잘못된 메시지와 계속 실패하는 메시지를 DLT로 보내는지 실제 Kafka와 PostgreSQL(Testcontainers)로 확인한다. Docker 필요
// 메시지 키를 테스트마다 다른 공모주 ID로 보내서 같은 토픽을 공유해도 서로 영향이 없고, DLT도 이 키로 구분한다
// 확정 청약 목록은 청약 서비스를 띄우지 않고 가짜 Reader로 넘긴다
// 클래스가 끝나면 컨텍스트를 닫아서(@DirtiesContext), 종료된 Kafka 컨테이너에 리스너와 Producer가 계속 접속을 시도하지 않게 한다
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {"eureka.client.enabled=false", "spring.kafka.listener.auto-startup=true"})
class IpoClosedConsumerKafkaTest {

    private static final String TOPIC = IpoClosedMessage.TOPIC;
    private static final String DLT_TOPIC = TOPIC + ".DLT";
    // 재시도(1초, 2초, 4초)가 일어났다면 DLT 도착까지 7초 이상 걸리므로, 이보다 충분히 짧은 시간 안에 도착하면 재시도 없이 보낸 것이다
    private static final Duration NO_RETRY_LIMIT = Duration.ofSeconds(5);
    private static final long RETRY_BACKOFF_MILLIS = 7_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "allocation");

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
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoBean
    private ConfirmedSubscriptionReader confirmedSubscriptionReader;

    private UUID ipoId;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
    }

    // 정상 메시지는 배정을 실행해 회차를 DONE으로 바꾸고 DLT로 가지 않는다
    @Test
    void allocatesWhenMessageIsValid() {
        when(confirmedSubscriptionReader.readAll(ipoId)).thenReturn(subscriptions(3));

        UUID eventId = UUID.randomUUID();

        send(validJson(eventId, 3));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> assertThat(roundStatus()).isEqualTo("DONE"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM allocation WHERE ipo_id = ?", Long.class, ipoId))
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT consumer_name FROM processed_event WHERE event_id = ?", String.class, eventId))
                .isEqualTo("allocation-ipo-closed");
        assertThat(readDlt(Duration.ofSeconds(3), Integer.MAX_VALUE)).isEmpty();
    }

    // 명세 범위를 벗어난 메시지(균등 물량 > 일반청약 물량)는 재시도 없이 바로 DLT로 가고 원본 JSON이 그대로 쌓인다. 회차는 만들지 않는다
    @Test
    void sendsToDltWithoutRetryWhenMessageIsInvalid() {
        String payload = """
                {"eventId":"%s","eventType":"ipo.closed","occurredAt":"2026-11-04T16:01:00.000+09:00",
                 "ipoId":"%s","closedAt":"2026-11-04T16:00:00.000+09:00","confirmedCount":0,
                 "offerPrice":10000,"retailQuantity":100,"equalQuantity":101,"minQuantity":10}""".formatted(UUID.randomUUID(), ipoId);

        long startedAt = System.nanoTime();
        send(payload);

        List<ConsumerRecord<String, String>> dlt = readDlt(NO_RETRY_LIMIT, 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(dlt).singleElement().satisfies(record -> assertThat(record.value()).isEqualTo(payload));
        assertThat(elapsedMillis).isLessThan(NO_RETRY_LIMIT.toMillis() + 2_000);
        assertThat(roundCount()).isZero();
    }

    // 목록 조회가 계속 실패하면 재시도(1초, 2초, 4초)를 모두 쓴 뒤 DLT로 가고, 회차는 FAILED로 남는다
    @Test
    void sendsToDltAfterRetriesWhenReadingKeepsFailing() {
        when(confirmedSubscriptionReader.readAll(ipoId)).thenThrow(new IllegalStateException("청약 서비스 응답 없음"));
        String payload = validJson(UUID.randomUUID(), 3);

        long startedAt = System.nanoTime();
        send(payload);

        List<ConsumerRecord<String, String>> dlt = readDlt(Duration.ofSeconds(30), 1);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        assertThat(dlt).singleElement().satisfies(record -> assertThat(record.value()).isEqualTo(payload));
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(RETRY_BACKOFF_MILLIS);
        assertThat(roundStatus()).isEqualTo("FAILED");
    }

    // ipo.closed 리스너에 처리 시간 한도(15분, application.yml)와 한 번에 1건 받기가 적용된다
    @Test
    void appliesLongPollIntervalToIpoClosedListener() {
        MessageListenerContainer container = kafkaListenerEndpointRegistry.getListenerContainers().stream()
                .filter(c -> Arrays.asList(c.getContainerProperties().getTopics()).contains(TOPIC))
                .findFirst()
                .orElseThrow();

        Properties consumerProperties = container.getContainerProperties().getKafkaConsumerProperties();
        assertThat(consumerProperties.getProperty(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo("900000");
        assertThat(consumerProperties.getProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo("1");
    }

    private void send(String payload) {
        try {
            kafkaTemplate.send(TOPIC, ipoId.toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String validJson(UUID eventId, long confirmedCount) {
        return """
                {"eventId":"%s","eventType":"ipo.closed","occurredAt":"2026-11-04T16:01:00.000+09:00",
                 "ipoId":"%s","closedAt":"2026-11-04T16:00:00.000+09:00","confirmedCount":%d,
                 "offerPrice":10000,"retailQuantity":100,"equalQuantity":50,"minQuantity":10}""".formatted(eventId, ipoId, confirmedCount);
    }

    private List<ConfirmedSubscription> subscriptions(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ConfirmedSubscription(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 100L * (i + 1)))
                .toList();
    }

    // 메시지 키가 이 테스트의 공모주 ID인 DLT 메시지를 읽는다. 그룹에 참여하지 않고 파티션을 직접 지정해서 가입 대기 시간이 없다
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
                    if (ipoId.toString().equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }

    private String roundStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM allocation_round WHERE ipo_id = ?", String.class, ipoId);
    }

    private long roundCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM allocation_round WHERE ipo_id = ?", Long.class, ipoId);
    }
}
