package com.gongmoju.subscription.infrastructure.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gongmoju.subscription.application.subscription.SubscriptionVoidRetryService;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 최대 횟수 도달 알림 경로가 실제 서비스(저장소, 건별 처리 서비스)로 실행될 때 청약과 Outbox를 바꾸지 않는지를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * 알림 경로는 재요청 서비스({@code handle})를 재사용해 청약 정보를 얻으므로, {@code handle}이 최대 횟수에 이른 청약에서 아무것도 바꾸지 않는다는 전제에 의존함.
 * 스케줄러 단위 테스트는 {@code handle}을 가짜로 대신해서 이 전제를 확인하지 못하므로, 이 테스트가 전제가 깨지는 것(알림 중 재요청이 나가는 것)을 잡아냄.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("최대 횟수 도달 알림 경로는 청약과 Outbox를 바꾸지 않는다 (PostgreSQL)")
class VoidRetryAlertNoWriteTest {

    private static final Duration ALERT_INTERVAL = Duration.ofMinutes(5);
    private static final long MARGIN_AMOUNT = 1_500_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionVoidRetryService voidRetryService;

    @Autowired
    private PendingTimeoutProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;
    private UUID subscriptionId;
    private Logger schedulerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        schedulerLogger = (Logger) LoggerFactory.getLogger(VoidRetryScheduler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        schedulerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(logAppender);
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", subscriptionId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @CsvSource({"COMPENSATING, TIMEOUT", "COMPENSATING, CONFIRM_FAILED", "CANCEL_REQUESTED, USER_CANCEL"})
    @DisplayName("최대 횟수에 이른 청약에 알림 경로를 실행하면 ERROR 로그만 남고 상태, 요청 횟수, 요청 시각, version, Outbox가 그대로이다")
    void alertPath_changesNothing_forExhaustedSubscription(String status, String cancelReason) {
        // given
        VoidRetryScheduler scheduler = new VoidRetryScheduler(subscriptionRepository, voidRetryService, properties, ALERT_INTERVAL);
        Instant lastRequestedAt = Instant.now().minus(properties.responseTimeout()).minusSeconds(10).truncatedTo(ChronoUnit.MICROS);
        insertSubscription(status, cancelReason, properties.maxAttempts(), lastRequestedAt);
        Map<String, Object> before = subscription();

        // when
        scheduler.retryStaleVoid(Instant.now());

        // then: 알림은 실제로 나갔고(ERROR 로그), 청약과 Outbox는 바뀌지 않음
        List<ILoggingEvent> errors = logAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains(subscriptionId.toString(), status));
        assertThat(subscription()).isEqualTo(before);
        assertThat(subscription().get("attempts")).isEqualTo(properties.maxAttempts());
        assertThat(subscription().get("version")).isEqualTo(0L);
        assertThat(outboxCount()).isZero();
    }

    private void insertSubscription(String status, String cancelReason, int attempts, Instant lastRequestedAt) {
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, ?, ?, ?, ?, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), MARGIN_AMOUNT, status, cancelReason,
                attempts, Timestamp.from(lastRequestedAt));
    }

    private Map<String, Object> subscription() {
        return jdbcTemplate.queryForMap("""
                SELECT status, cancel_reason, attempts, last_requested_at, version, updated_at
                FROM subscription WHERE subscription_id = ?""", subscriptionId);
    }

    private long outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Long.class, subscriptionId);
    }
}
