package com.gongmoju.subscription.application.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 접수 처리(검증, 저장, 증거금 확보 요청 기록)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("접수 처리 (PostgreSQL)")
class SubscriptionAcceptServiceTest {

    private static final long OFFER_PRICE = 15_000;
    private static final String RESERVE_TOPIC = "margin.reserve-requested";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionAcceptService acceptService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID ipoId;
    private UUID userId;
    private UUID accountId;
    private Instant receivedAt;

    /**
     * 공모주 생성 메서드가 없으므로(시드 데이터만 사용) SQL로 직접 저장함.
     */
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', ?, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId, OFFER_PRICE);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)", ipoId);
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("새 요청은 PENDING으로 저장하고, 증거금 확보 요청을 Outbox에 기록한다")
    void accept_savesPendingAndRecordsOutbox_whenNewRequest() throws Exception {
        // given
        SubscriptionRequested event = event(UUID.randomUUID(), userId, 100);

        // when
        AcceptResult result = acceptService.accept(event);

        // then
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        Map<String, Object> subscription = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason, margin_amount, attempts, last_requested_at, received_at FROM subscription WHERE request_id = ?",
                event.requestId());
        assertThat(subscription.get("status")).isEqualTo("PENDING");
        assertThat(subscription.get("reject_reason")).isNull();
        assertThat(((Number) subscription.get("margin_amount")).longValue()).isEqualTo(100 * OFFER_PRICE);
        assertThat(((Number) subscription.get("attempts")).intValue()).isEqualTo(1);
        assertThat(subscription.get("last_requested_at")).isNotNull();

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT topic, event_type, message_key, aggregate_type, aggregate_id, published_at, payload::text AS payload FROM outbox WHERE aggregate_id = ?",
                event.subscriptionId());
        assertThat(outbox.get("topic")).isEqualTo(RESERVE_TOPIC);
        assertThat(outbox.get("event_type")).isEqualTo(RESERVE_TOPIC);
        assertThat(outbox.get("message_key")).isEqualTo(accountId.toString());
        assertThat(outbox.get("aggregate_type")).isEqualTo("SUBSCRIPTION");
        assertThat(outbox.get("published_at")).isNull();
        JsonNode payload = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.get("subscriptionId").asText()).isEqualTo(event.subscriptionId().toString());
        assertThat(payload.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(payload.get("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(payload.get("ipoId").asText()).isEqualTo(ipoId.toString());
        assertThat(payload.get("amount").asLong()).isEqualTo(100 * OFFER_PRICE);
    }

    /**
     * 현행 동작 기록: 접수 Consumer는 receivedAt이 마감 시각 전인지만 보고 공모주 상태는 보지 않는다.
     * 마감 전에 받은 요청의 메시지가 마감 뒤에 처리되면 마감된 공모주에 PENDING 청약이 생겨 마감 때 센 확정 수에서 빠진다
     * (이벤트 명세 4장 위험 4). 고칠 대상을 기록하는 테스트이고, 접수 Outbox(D-29)에서 접수 Consumer와 함께 지운다.
     */
    @Test
    @DisplayName("현행 동작 기록: 공모주가 이미 CLOSED여도 receivedAt이 마감 전이면 PENDING으로 저장하고 인출 요청을 기록한다")
    void accept_savesPending_evenWhenIpoAlreadyClosed() {
        // given: 마감 시각(지금 - 10초)이 지나 CLOSED로 바뀐 공모주, 마감 전(지금 - 60초)에 게이트웨이에 도착한 요청
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED', subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);
        SubscriptionRequested event = event(UUID.randomUUID(), userId, 100);

        // when
        AcceptResult result = acceptService.accept(event);

        // then
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        assertThat(subscriptionCount("PENDING")).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ipo WHERE ipo_id = ?", String.class, ipoId)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("같은 requestId로 같은 내용이 다시 오면 아무것도 저장하지 않고 무시하며, 처음 receivedAt이 유지된다")
    void accept_ignores_whenSameRequestIsResent() {
        // given
        UUID requestId = UUID.randomUUID();
        acceptService.accept(event(requestId, userId, 100));
        SubscriptionRequested resent = new SubscriptionRequested(requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, accountId, ipoId, 100, receivedAt.plusSeconds(5));

        // when
        AcceptResult result = acceptService.accept(resent);

        // then
        assertThat(result).isEqualTo(AcceptResult.DUPLICATE_IGNORED);
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
        Instant savedReceivedAt = jdbcTemplate.queryForObject(
                "SELECT received_at FROM subscription WHERE request_id = ?", java.sql.Timestamp.class, requestId).toInstant();
        assertThat(savedReceivedAt).isEqualTo(receivedAt);
    }

    @Test
    @DisplayName("같은 requestId인데 요청 내용이 다르면 InvalidEventException이 나고 아무것도 더 저장하지 않는다")
    void accept_throws_whenSameRequestIdHasDifferentContent() {
        // given
        UUID requestId = UUID.randomUUID();
        acceptService.accept(event(requestId, userId, 100));

        // when, then
        assertThatThrownBy(() -> acceptService.accept(event(requestId, userId, 200)))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("requestId");
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 회원이 같은 종목에 유효한 청약이 있는데 다른 requestId로 요청하면 REJECTED(ALREADY_SUBSCRIBED)로 저장하고 Outbox에는 기록하지 않는다")
    void accept_rejects_whenUserAlreadyHasActiveSubscription() {
        // given
        acceptService.accept(event(UUID.randomUUID(), userId, 100));
        SubscriptionRequested second = event(UUID.randomUUID(), userId, 50);

        // when
        AcceptResult result = acceptService.accept(second);

        // then
        assertThat(result).isEqualTo(AcceptResult.REJECTED_ALREADY_SUBSCRIBED);
        Map<String, Object> rejected = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason, margin_amount, attempts, last_requested_at FROM subscription WHERE request_id = ?",
                second.requestId());
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("reject_reason")).isEqualTo("ALREADY_SUBSCRIBED");
        assertThat(((Number) rejected.get("margin_amount")).longValue()).isEqualTo(50 * OFFER_PRICE);
        assertThat(((Number) rejected.get("attempts")).intValue()).isZero();
        assertThat(rejected.get("last_requested_at")).isNull();
        assertThat(subscriptionCount()).isEqualTo(2);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 청약이 CANCELLED이면 같은 회원이 다시 청약할 수 있다 (재청약)")
    void accept_acceptsAgain_whenPreviousSubscriptionWasCancelled() {
        // given
        SubscriptionRequested first = event(UUID.randomUUID(), userId, 100);
        acceptService.accept(first);
        jdbcTemplate.update("UPDATE subscription SET status = 'CANCELLED', cancel_reason = 'USER_CANCEL' WHERE request_id = ?",
                first.requestId());

        // when
        AcceptResult result = acceptService.accept(event(UUID.randomUUID(), userId, 100));

        // then
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        assertThat(outboxCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("subscriptionId가 userId와 requestId로 계산한 값과 다르면 InvalidEventException이 나고 아무것도 저장하지 않는다")
    void accept_throws_whenSubscriptionIdDoesNotMatch() {
        // given
        UUID requestId = UUID.randomUUID();
        SubscriptionRequested tampered = new SubscriptionRequested(requestId, UUID.randomUUID(), userId, accountId,
                ipoId, 100, receivedAt);

        // when, then
        assertThatThrownBy(() -> acceptService.accept(tampered)).isInstanceOf(InvalidEventException.class);
        assertThat(subscriptionCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("종목이 없으면 InvalidEventException이 나고 아무것도 저장하지 않는다")
    void accept_throws_whenIpoDoesNotExist() {
        // given
        UUID requestId = UUID.randomUUID();
        SubscriptionRequested unknownIpo = new SubscriptionRequested(requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, accountId, UUID.randomUUID(), 100, receivedAt);

        // when, then
        assertThatThrownBy(() -> acceptService.accept(unknownIpo)).isInstanceOf(InvalidEventException.class);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("receivedAt이 청약 마감 시각 이후이면 InvalidEventException이 나고 아무것도 저장하지 않는다")
    void accept_throws_whenReceivedAtIsAfterEnd() {
        // given
        UUID requestId = UUID.randomUUID();
        SubscriptionRequested late = new SubscriptionRequested(requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, accountId, ipoId, 100,
                Instant.now().plus(2, ChronoUnit.DAYS));

        // when, then
        assertThatThrownBy(() -> acceptService.accept(late)).isInstanceOf(InvalidEventException.class);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("증거금 계산이 long 범위를 넘으면 InvalidEventException이 나고 아무것도 저장하지 않는다")
    void accept_throws_whenMarginAmountOverflows() {
        // given
        SubscriptionRequested overflow = event(UUID.randomUUID(), userId, Long.MAX_VALUE);

        // when, then
        assertThatThrownBy(() -> acceptService.accept(overflow))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("long 범위");
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("같은 회원의 두 요청이 동시에 처리되어도 유효한 청약은 한 건이고, 위반으로 실패한 요청은 재처리하면 REJECTED로 저장된다")
    void accept_keepsOnlyOneActiveSubscription_whenSameUserRequestsConcurrently() throws Exception {
        // given
        SubscriptionRequested first = event(UUID.randomUUID(), userId, 100);
        SubscriptionRequested second = event(UUID.randomUUID(), userId, 100);

        // when
        List<Object> outcomes = acceptConcurrently(first, second);
        // 동시에 처리되어 `uk_subscription_active` 위반이 난 요청은 Consumer가 재시도하는 것과 같이 다시 처리함
        // 타이밍에 따라 위반 없이 순차 처리될 수도 있으며, 그 경우 한쪽은 처음부터 `REJECTED_ALREADY_SUBSCRIBED`임
        for (int i = 0; i < outcomes.size(); i++) {
            if (outcomes.get(i) instanceof Throwable failure) {
                assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
                outcomes.set(i, acceptService.accept(i == 0 ? first : second));
            }
        }

        // then
        assertThat(outcomes).containsExactlyInAnyOrder(AcceptResult.ACCEPTED, AcceptResult.REJECTED_ALREADY_SUBSCRIBED);
        assertThat(subscriptionCount("PENDING")).isEqualTo(1);
        assertThat(subscriptionCount("REJECTED")).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    private SubscriptionRequested event(UUID requestId, UUID user, long requestedQuantity) {
        return new SubscriptionRequested(requestId, SubscriptionIdGenerator.generate(user, requestId), user, accountId,
                ipoId, requestedQuantity, receivedAt);
    }

    /**
     * 두 스레드가 같은 시점에 각자의 트랜잭션으로 처리함. 결과는 처리 결과(AcceptResult) 또는 던져진 예외(Throwable)이며 순서는 제출 순서와 같음.
     */
    private List<Object> acceptConcurrently(SubscriptionRequested first, SubscriptionRequested second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<AcceptResult>> futures = Stream.of(first, second)
                    .map(event -> executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return acceptService.accept(event);
                    }))
                    .toList();
            List<Object> outcomes = new ArrayList<>();
            for (Future<AcceptResult> future : futures) {
                try {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    outcomes.add(e.getCause());
                }
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private int subscriptionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ?", Integer.class, ipoId);
    }

    private int subscriptionCount(String status) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ? AND status = ?", Integer.class, ipoId, status);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox
                WHERE topic = ? AND aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)""",
                Integer.class, RESERVE_TOPIC, ipoId);
    }
}
