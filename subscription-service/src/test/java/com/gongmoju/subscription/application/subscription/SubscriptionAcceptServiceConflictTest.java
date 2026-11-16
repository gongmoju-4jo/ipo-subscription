package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("접수 처리 저장 충돌 (PostgreSQL)")
class SubscriptionAcceptServiceConflictTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @MockitoSpyBean
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private SubscriptionAcceptService acceptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ipoId;

    /**
     * 공모주 생성 메서드를 거치지 않고 SQL로 직접 저장함 (SubscriptionAcceptServiceRollbackTest와 같음).
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
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
    @DisplayName("저장이 충돌했는데 확인할 때는 같은 requestId도 유효 청약도 없으면(충돌한 유효 청약이 그사이 거절, 취소됨) 다시 저장해 PENDING으로 접수한다")
    void accept_savesAgain_whenConflictingActiveSubscriptionEndedBeforeCheck() {
        // given: 첫 저장은 유효 청약과 충돌해 0행이었고, 확인하기 전에 그 청약이 끝난 상황. 확인 시점에 DB에는 충돌 상대가 없음
        UUID userId = UUID.randomUUID();
        SubscribeCommand command = new SubscribeCommand(UUID.randomUUID(), ipoId, UUID.randomUUID(), 100);
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        doReturn(false).doCallRealMethod().when(subscriptionRepository).insertIfAbsent(any());

        // when
        AcceptResult result = acceptService.accept(SubscriptionIdGenerator.generate(userId, command.requestId()), userId, receivedAt, command);

        // then: 500이 아니라 두 번째 저장으로 접수되고 인출 요청도 기록됨
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        verify(subscriptionRepository, times(2)).insertIfAbsent(any());
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM subscription WHERE request_id = ?", String.class, command.requestId()))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)""",
                Integer.class, ipoId)).isEqualTo(1);
    }
}
