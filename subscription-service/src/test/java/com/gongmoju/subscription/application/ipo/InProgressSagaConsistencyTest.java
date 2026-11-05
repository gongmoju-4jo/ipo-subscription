package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 마감이 기다리는 "진행 중 SAGA" 상태와 멈춘 SAGA를 정리하는 스케줄러(PENDING 응답 시간 초과 처리, 반환 재요청)가 다루는 상태가 같은지 실제 PostgreSQL(Testcontainers)로 검증함.
 * 마감은 진행 중인 청약이 남아 있으면 강제로 마감하지 않으므로(결정 14), 마감을 막는 상태가 정리 대상에서 빠지면 영원히 마감되지 않음.
 * 상태가 새로 생기면 이 테스트가 두 쪽 중 한쪽만 고친 것을 잡아냄. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("마감의 진행 중 SAGA 판정과 멈춘 SAGA 정리 대상의 일치 (PostgreSQL)")
class InProgressSagaConsistencyTest {

    private static final int MAX_ATTEMPTS = 3;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private IpoCloseService ipoCloseService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SubscriptionStatus.class)
    @DisplayName("모든 청약 상태에 대해, 마감을 막는 상태이면 응답 시간이 지난 청약이 정리 스케줄러의 대상이고, 아니면 대상이 아니다")
    void closeBlockingStatuses_areExactlyThoseTheCleanupSchedulersHandle(SubscriptionStatus status) {
        // given
        ipoId = UUID.randomUUID();
        insertOpenIpo();
        UUID subscriptionId = insertSubscription(status, Instant.now().minusSeconds(60));
        Instant threshold = Instant.now().minusSeconds(30);

        // when
        boolean blocksClose = ipoCloseService.close(ipoId).outcome() == IpoCloseResult.Outcome.IN_PROGRESS_REMAINING;
        boolean handledByCleanup = subscriptionRepository.findStalePendingIds(threshold, 100).contains(subscriptionId)
                || subscriptionRepository.findStaleVoidIds(threshold, MAX_ATTEMPTS, 100).contains(subscriptionId);

        // then
        assertThat(handledByCleanup)
                .as("%s: 마감을 막는 상태(%s)와 정리 스케줄러 대상(%s)이 같아야 함", status, blocksClose, handledByCleanup)
                .isEqualTo(blocksClose);
    }

    private void insertOpenIpo() {
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
    }

    // 해제 사유는 COMPENSATING, CANCEL_REQUESTED, CANCELLED에서만 필요함. 반환 요청 Outbox가 없어서(발행 완료로 간주) 조회 대상이 된다
    private UUID insertSubscription(SubscriptionStatus status, Instant lastRequestedAt) {
        UUID id = UUID.randomUUID();
        String cancelReason = switch (status) {
            case COMPENSATING, CANCELLED -> "TIMEOUT";
            case CANCEL_REQUESTED -> "USER_CANCEL";
            default -> null;
        };
        jdbcTemplate.update("""
                INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,
                                          margin_amount, status, cancel_reason, attempts, last_requested_at, received_at,
                                          version, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, ?, 1, ?, now() - interval '10 minutes',
                        0, now(), 'test', now(), 'test')""",
                id, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), status.name(), cancelReason,
                Timestamp.from(lastRequestedAt));
        return id;
    }
}
