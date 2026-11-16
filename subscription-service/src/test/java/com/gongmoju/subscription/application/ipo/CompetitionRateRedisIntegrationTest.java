package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.application.subscription.ConfirmResult;
import com.gongmoju.subscription.application.subscription.SubscriptionConfirmService;
import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;
import com.gongmoju.subscription.infrastructure.redis.CompetitionRateProperties;
import com.gongmoju.subscription.infrastructure.redis.RedisCompetitionRateCounter;
import com.gongmoju.subscription.domain.subscription.MarginReserved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경쟁률 사본(Redis)을 실제 PostgreSQL, Redis(Testcontainers)로 확인함 (F-15). Docker 필요.
 * 사본이 없으면 DB로 채우고, 확정이 커밋되면 더하고, 사본 값이 DB 합산과 같은지 본다.
 */
@Testcontainers
@SpringBootTest(properties = {"eureka.client.enabled=false", "ipo.competition-rate.redis-enabled=true"})
@DisplayName("경쟁률 사본 (PostgreSQL, Redis)")
class CompetitionRateRedisIntegrationTest {

    // 물량 100,000주, 확정 청약 합 1,527,000주 → 15.27
    private static final long RETAIL = 100_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redisContainer = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    private IpoService ipoService;

    @Autowired
    private SubscriptionConfirmService confirmService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CompetitionRateCounter counter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID ipoId;
    private String key;
    private String loadingKey;

    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        key = "competition:requested:" + ipoId;
        loadingKey = "competition:loading:" + ipoId;
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '경쟁률 사본', 1000, ?, ?, 1, 10000000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId, RETAIL, RETAIL / 2);
        insertSubscription(UUID.randomUUID(), "CONFIRMED", 1_000_000);
        insertSubscription(UUID.randomUUID(), "ALLOCATED", 500_000);
        insertSubscription(UUID.randomUUID(), "REFUNDED", 27_000);
        insertSubscription(UUID.randomUUID(), "PENDING", 9_000_000);  // 확정 전이라 세지 않음
        insertSubscription(UUID.randomUUID(), "CANCEL_REQUESTED", 9_000_000);  // 배정 대상이 아니라 세지 않음
    }

    @AfterEach
    void tearDown() {
        redis.delete(List.of(key, loadingKey));
        jdbcTemplate.update("DELETE FROM processed_event WHERE consumer_name = ?", SubscriptionConfirmService.CONSUMER_NAME);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("사본이 없으면 DB로 합산해 응답하고 그 값을 유효 시간과 함께 사본에 넣는다")
    void get_loadsFromDatabase_andStoresWithTtl_whenAbsent() {
        // when
        BigDecimal rate = ipoService.get(ipoId).competitionRate();

        // then
        assertThat(rate).isEqualByComparingTo("15.27");
        assertThat(redis.opsForValue().get(key)).isEqualTo("1527000");
        assertThat(redis.getExpire(key)).isPositive();
    }

    @Test
    @DisplayName("사본이 있으면 사본 값으로 계산한다 (DB를 다시 합산하지 않음)")
    void get_usesCounter_whenPresent() {
        // given: DB와 다른 값을 넣어 어느 쪽을 읽는지 구분
        redis.opsForValue().set(key, "200000");

        // when, then
        assertThat(ipoService.get(ipoId).competitionRate()).isEqualByComparingTo("2.00");
        assertThat(ipoService.list(org.springframework.data.domain.PageRequest.of(0, 100)).getContent().stream()
                .filter(r -> r.ipoId().equals(ipoId)).findFirst().orElseThrow().competitionRate()).isEqualByComparingTo("2.00");
    }

    @Test
    @DisplayName("확정이 커밋되면 사본에 신청 수량을 더하고, 사본 값은 DB 합산과 같다. 같은 이벤트를 다시 받으면 더하지 않는다")
    void confirm_addsToCounterAfterCommit_andMatchesDatabase() {
        // given: 사본을 채우고, 확정할 PENDING 청약
        ipoService.get(ipoId);
        UUID pending = UUID.randomUUID();
        insertSubscription(pending, "PENDING", 3_000);
        MarginReserved event = event(pending, 3_000L * 1000);

        // when
        assertThat(confirmService.confirm(event)).isEqualTo(ConfirmResult.CONFIRMED);
        confirmService.confirm(event);  // 같은 이벤트 재수신

        // then: 1,527,000 + 3,000. DB 합산과 같음
        assertThat(redis.opsForValue().get(key)).isEqualTo("1530000");
        assertThat(Long.parseLong(redis.opsForValue().get(key))).isEqualTo(databaseSum());
        assertThat(ipoService.get(ipoId).competitionRate()).isEqualByComparingTo("15.30");
    }

    @Test
    @DisplayName("확정이 롤백되면 사본에 더하지 않는다")
    void confirm_doesNotAdd_whenRolledBack() {
        // given
        ipoService.get(ipoId);
        UUID pending = UUID.randomUUID();
        insertSubscription(pending, "PENDING", 3_000);

        // when: 바깥 트랜잭션에 참여한 확정을 롤백
        transactionTemplate.executeWithoutResult(status -> {
            confirmService.confirm(event(pending, 3_000L * 1000));
            status.setRollbackOnly();
        });

        // then
        assertThat(redis.opsForValue().get(key)).isEqualTo("1527000");
    }

    @Test
    @DisplayName("사본이 없을 때 확정되면 더하지 않고, 다음 조회가 DB로 채운다 (Redis 재시작 등)")
    void confirm_skips_whenCounterAbsent_thenNextReadLoadsFromDatabase() {
        // given: 사본 없음
        UUID pending = UUID.randomUUID();
        insertSubscription(pending, "PENDING", 3_000);

        // when
        confirmService.confirm(event(pending, 3_000L * 1000));

        // then: 확정 한 건만 든 값이 생기지 않음
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(ipoService.get(ipoId).competitionRate()).isEqualByComparingTo("15.30");
        assertThat(redis.opsForValue().get(key)).isEqualTo("1530000");
    }

    @Test
    @DisplayName("채우는 중에 확정된 청약은 채우는 중 합계에 모였다가 사본에 합쳐진다 (DB 합산 뒤 확정이 빠지지 않음)")
    void confirmDuringLoading_isMergedIntoCounter() {
        // given: 채우기 시작(표시) → DB 합산(1,527,000) → 그사이 확정 3,000주
        assertThat(counter.startLoading(List.of(ipoId))).containsExactly(ipoId);
        long sumBeforeConfirm = databaseSum();
        UUID pending = UUID.randomUUID();
        insertSubscription(pending, "PENDING", 3_000);
        confirmService.confirm(event(pending, 3_000L * 1000));

        // when: 확정 전에 합산한 값으로 채움
        counter.finishLoading(Map.of(ipoId, sumBeforeConfirm));

        // then: 확정이 빠지지 않고 DB 합산과 같음, 채우는 중 표시는 지워짐
        assertThat(redis.opsForValue().get(key)).isEqualTo("1530000");
        assertThat(Long.parseLong(redis.opsForValue().get(key))).isEqualTo(databaseSum());
        assertThat(redis.hasKey(loadingKey)).isFalse();
    }

    @Test
    @DisplayName("채우는 중 표시가 바뀐 뒤(만료 뒤 다른 인스턴스가 다시 채움) 늦게 끝난 채우기는 옛 값을 넣지 않고 남의 표시도 지우지 않는다")
    void staleLoader_cannotStoreOrDeleteAnotherLoadersMarker() {
        // given: 이 인스턴스가 채우기 시작 → 표시가 만료돼 다른 인스턴스가 새로 시작
        assertThat(counter.startLoading(List.of(ipoId))).containsExactly(ipoId);
        redis.delete(loadingKey);
        RedisCompetitionRateCounter other = new RedisCompetitionRateCounter(redis,
                new CompetitionRateProperties(true, Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(10)), Clock.systemUTC());
        assertThat(other.startLoading(List.of(ipoId))).containsExactly(ipoId);

        // when: 옛 채우기가 늦게 끝남
        counter.finishLoading(Map.of(ipoId, 100L));

        // then: 사본을 넣지 않고 다른 인스턴스의 표시도 남아 있음. 다른 인스턴스는 정상으로 넣음
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey(loadingKey)).isTrue();
        other.finishLoading(Map.of(ipoId, 1_527_000L));
        assertThat(redis.opsForValue().get(key)).isEqualTo("1527000");
        assertThat(redis.hasKey(loadingKey)).isFalse();
    }

    @Test
    @DisplayName("다른 쪽이 채우는 중이면 채우기를 맡지 않는다")
    void startLoading_doesNotOwn_whenAlreadyLoading() {
        assertThat(counter.startLoading(List.of(ipoId))).containsExactly(ipoId);
        assertThat(counter.startLoading(List.of(ipoId))).isEmpty();
        assertThat(redis.getExpire(loadingKey)).isPositive();  // 채우던 쪽이 죽어도 풀리게
    }

    private long databaseSum() {
        return jdbcTemplate.queryForObject("SELECT coalesce(sum(requested_quantity), 0) FROM subscription WHERE ipo_id = ?"
                + " AND status IN ('CONFIRMED', 'ALLOCATED', 'REFUNDED')", Long.class, ipoId);
    }

    private MarginReserved event(UUID subscriptionId, long amount) {
        return new MarginReserved(UUID.randomUUID(), subscriptionId, UUID.randomUUID(), amount,
                Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    private void insertSubscription(UUID subscriptionId, String status, long requestedQuantity) {
        jdbcTemplate.update(
                "INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id, requested_quantity,"
                        + " margin_amount, status, attempts, received_at, version, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, now(), 0, now(), 'SYSTEM:test')",
                subscriptionId, UUID.randomUUID(), ipoId, UUID.randomUUID(), UUID.randomUUID(), requestedQuantity,
                requestedQuantity * 1000, status);
    }
}
