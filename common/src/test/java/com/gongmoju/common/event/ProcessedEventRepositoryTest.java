package com.gongmoju.common.event;

import com.gongmoju.common.testapp.CommonTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ON CONFLICT (event_id, consumer_name)} 동작과 트랜잭션 롤백을 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(classes = CommonTestApplication.class)
@DisplayName("처리한 이벤트 저장소 (PostgreSQL, common 자동 설정으로 등록)")
class ProcessedEventRepositoryTest {

    private static final String CONSUMER = "test-margin-reserved";
    private static final String OTHER_CONSUMER = "test-margin-reserve-failed";
    private static final String TOPIC = "margin.reserved";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name IN (?, ?)", CONSUMER, OTHER_CONSUMER);
    }

    @Test
    @DisplayName("처음 보는 이벤트는 저장되고 true를 반환하며 Consumer 이름, 토픽, 처리 시각과 함께 남는다")
    void insertIfAbsent_savesAndReturnsTrue_whenEventIsNew() {
        // given
        UUID eventId = UUID.randomUUID();

        // when
        boolean inserted = insert(eventId, CONSUMER);

        // then
        assertThat(inserted).isTrue();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT consumer_name, topic, processed_at FROM processed_event WHERE event_id = ?", eventId))
                .containsEntry("consumer_name", CONSUMER)
                .containsEntry("topic", TOPIC)
                .doesNotContainEntry("processed_at", null);
    }

    @Test
    @DisplayName("같은 eventId와 Consumer 이름을 다시 저장하면 예외 없이 false를 반환하고 행은 1개로 유지된다")
    void insertIfAbsent_returnsFalse_whenSameEventAndConsumer() {
        // given
        UUID eventId = UUID.randomUUID();
        insert(eventId, CONSUMER);

        // when
        boolean inserted = insert(eventId, CONSUMER);

        // then
        assertThat(inserted).isFalse();
        assertThat(count(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 eventId라도 Consumer 이름이 다르면 각각 저장된다")
    void insertIfAbsent_savesBoth_whenSameEventButDifferentConsumer() {
        // given
        UUID eventId = UUID.randomUUID();

        // when
        boolean first = insert(eventId, CONSUMER);
        boolean second = insert(eventId, OTHER_CONSUMER);

        // then
        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(count(eventId)).isEqualTo(2);
    }

    @Test
    @DisplayName("중복으로 false가 나와도 같은 트랜잭션에서 이어서 쿼리를 실행할 수 있다")
    void insertIfAbsent_keepsTransactionUsable_whenDuplicate() {
        // given
        UUID eventId = UUID.randomUUID();
        insert(eventId, CONSUMER);

        // when
        Integer count = transactionTemplate.execute(status -> {
            processedEventRepository.insertIfAbsent(eventId, CONSUMER, TOPIC);
            return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
        });

        // then
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 트랜잭션이 롤백되면 처리 기록도 남지 않아 다시 저장할 수 있다")
    void insertIfAbsent_rollsBackTogetherWithTransaction() {
        // given
        UUID eventId = UUID.randomUUID();

        // when
        transactionTemplate.executeWithoutResult(status -> {
            processedEventRepository.insertIfAbsent(eventId, CONSUMER, TOPIC);
            status.setRollbackOnly();
        });
        boolean inserted = insert(eventId, CONSUMER);

        // then
        assertThat(inserted).isTrue();
        assertThat(count(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이벤트를 동시에 저장하면 한 쪽만 true를 받고 행은 1개이며 예외가 발생하지 않는다")
    void insertIfAbsent_savesOnlyOnce_whenConcurrent() throws Exception {
        // given
        UUID eventId = UUID.randomUUID();
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);

        try {
            // when
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return insert(eventId, CONSUMER);
                }));
            }
            long trueCount = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    trueCount++;
                }
            }

            // then
            assertThat(trueCount).isEqualTo(1);
            assertThat(count(eventId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean insert(UUID eventId, String consumerName) {
        return Boolean.TRUE.equals(transactionTemplate.execute(
                status -> processedEventRepository.insertIfAbsent(eventId, consumerName, TOPIC)));
    }

    private int count(UUID eventId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId);
    }
}
