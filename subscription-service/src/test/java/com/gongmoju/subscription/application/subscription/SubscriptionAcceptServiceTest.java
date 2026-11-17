package com.gongmoju.subscription.application.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.subscription.application.ipo.IpoCloseResult;
import com.gongmoju.subscription.application.ipo.IpoCloseService;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 청약 API 접수(검증, 저장, 증거금 인출 요청 기록, 잠금 순서)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
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

    @Autowired
    private IpoCloseService closeService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    @DisplayName("API: 새 요청은 PENDING으로 저장하고, 증거금 인출 요청을 Outbox에 기록한다")
    void acceptApi_savesPendingAndRecordsOutbox_whenNewRequest() throws Exception {
        // given
        SubscribeCommand command = command(UUID.randomUUID(), 100);

        // when
        AcceptResult result = accept(userId, receivedAt, command);

        // then
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        UUID subscriptionId = SubscriptionIdGenerator.generate(userId, command.requestId());
        Map<String, Object> subscription = jdbcTemplate.queryForMap(
                "SELECT subscription_id, status, margin_amount, received_at FROM subscription WHERE request_id = ?", command.requestId());
        assertThat(subscription.get("subscription_id")).isEqualTo(subscriptionId);
        assertThat(subscription.get("status")).isEqualTo("PENDING");
        assertThat(((Number) subscription.get("margin_amount")).longValue()).isEqualTo(100 * OFFER_PRICE);
        assertThat(((java.sql.Timestamp) subscription.get("received_at")).toInstant()).isEqualTo(receivedAt);
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT topic, message_key, payload::text AS payload FROM outbox WHERE aggregate_id = ?", subscriptionId);
        assertThat(outbox.get("topic")).isEqualTo(RESERVE_TOPIC);
        assertThat(outbox.get("message_key")).isEqualTo(accountId.toString());
        assertThat(objectMapper.readTree((String) outbox.get("payload")).get("amount").asLong()).isEqualTo(100 * OFFER_PRICE);
    }

    @Test
    @DisplayName("API: 같은 requestId로 같은 내용이 다시 오면 새로 저장하지 않는다")
    void acceptApi_ignores_whenSameRequestRepeated() {
        // given
        SubscribeCommand command = command(UUID.randomUUID(), 100);
        accept(userId, receivedAt, command);

        // when
        AcceptResult result = accept(userId, receivedAt.plusSeconds(3), command);

        // then
        assertThat(result).isEqualTo(AcceptResult.DUPLICATE_IGNORED);
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 접수된 요청이 마감 뒤에 같은 내용으로 다시 오면 상태, 기간 검증에서 거절되지 않고 접수된 것으로 응답한다")
    void acceptApi_ignoresRepeat_evenAfterIpoClosed() {
        // given
        SubscribeCommand command = command(UUID.randomUUID(), 100);
        accept(userId, receivedAt, command);
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED', subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);

        // when
        AcceptResult result = accept(userId, Instant.now(), command);

        // then
        assertThat(result).isEqualTo(AcceptResult.DUPLICATE_IGNORED);
        assertThat(subscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 마감 뒤에 같은 requestId로 내용이 다르게 오면 마감 거절이 아니라 409 DUPLICATE_RESOURCE이다")
    void acceptApi_conflicts_whenRequestIdReusedWithDifferentContentAfterIpoClosed() {
        // given
        UUID requestId = UUID.randomUUID();
        accept(userId, receivedAt, command(requestId, 100));
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED', subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);

        // when, then
        assertThatThrownBy(() -> accept(userId, Instant.now(), command(requestId, 200)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
        assertThat(subscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 같은 requestId로 내용이 다르면 409 DUPLICATE_RESOURCE이고 기존 청약은 그대로다")
    void acceptApi_conflicts_whenSameRequestIdHasDifferentContent() {
        // given
        UUID requestId = UUID.randomUUID();
        accept(userId, receivedAt, command(requestId, 100));

        // when, then
        assertThatThrownBy(() -> accept(userId, receivedAt, command(requestId, 200)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
        assertThat(jdbcTemplate.queryForObject("SELECT requested_quantity FROM subscription WHERE request_id = ?", Long.class, requestId))
                .isEqualTo(100L);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 다른 회원이 이미 쓴 requestId면 409이고 기존 청약의 내용을 메시지에 드러내지 않는다")
    void acceptApi_conflicts_whenOtherUserUsedRequestId() {
        // given
        UUID requestId = UUID.randomUUID();
        accept(userId, receivedAt, command(requestId, 100));

        // when, then
        assertThatThrownBy(() -> accept(UUID.randomUUID(), receivedAt, command(requestId, 100)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용된 requestId입니다. 새 requestId로 다시 요청해 주세요.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.DUPLICATE_RESOURCE);
        assertThat(subscriptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("저장이 같은 requestId도 유효 청약도 아닌 이유로 계속 충돌하면 다시 저장해 본 뒤 예외이고 아무것도 더 저장하지 않는다 (모르는 충돌을 넘기지 않음)")
    void accept_throwsIllegalState_whenInsertConflictsForUnknownReason() {
        // given: 다른 requestId의 REJECTED 청약이 같은 청약 ID를 쓰고 있음 (PK 충돌, request_id와 유효 청약 충돌은 아님).
        // 청약 ID는 회원 ID와 requestId로 만들어서 실제로는 생기지 않는 상태를, 모르는 충돌을 만들려고 일부러 만듦
        UUID subscriptionId = UUID.randomUUID();
        acceptService.accept(subscriptionId, userId, receivedAt, command(UUID.randomUUID(), 100));
        jdbcTemplate.update("UPDATE subscription SET status = 'REJECTED', reject_reason = 'ALREADY_SUBSCRIBED' WHERE subscription_id = ?",
                subscriptionId);

        // when, then
        assertThatThrownBy(() -> acceptService.accept(subscriptionId, UUID.randomUUID(), receivedAt, command(UUID.randomUUID(), 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이중청약이 아닌 이유로 충돌");
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 같은 회원의 유효 청약이 이미 있으면 REJECTED(ALREADY_SUBSCRIBED)로 저장하고 인출 요청은 기록하지 않는다")
    void acceptApi_rejects_whenUserAlreadyHasActiveSubscription() {
        // given
        accept(userId, receivedAt, command(UUID.randomUUID(), 100));
        SubscribeCommand second = command(UUID.randomUUID(), 50);

        // when
        AcceptResult result = accept(userId, receivedAt, second);

        // then
        assertThat(result).isEqualTo(AcceptResult.REJECTED_ALREADY_SUBSCRIBED);
        Map<String, Object> rejected = jdbcTemplate.queryForMap(
                "SELECT status, reject_reason FROM subscription WHERE request_id = ?", second.requestId());
        assertThat(rejected.get("status")).isEqualTo("REJECTED");
        assertThat(rejected.get("reject_reason")).isEqualTo("ALREADY_SUBSCRIBED");
        assertThat(outboxCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("API: 이전 청약이 CANCELLED이면 같은 회원이 다시 청약할 수 있다 (재청약)")
    void acceptApi_acceptsAgain_whenPreviousSubscriptionWasCancelled() {
        // given
        SubscribeCommand first = command(UUID.randomUUID(), 100);
        accept(userId, receivedAt, first);
        jdbcTemplate.update("UPDATE subscription SET status = 'CANCELLED', cancel_reason = 'USER_CANCEL' WHERE request_id = ?",
                first.requestId());

        // when
        AcceptResult result = accept(userId, receivedAt, command(UUID.randomUUID(), 100));

        // then
        assertThat(result).isEqualTo(AcceptResult.ACCEPTED);
        assertThat(outboxCount()).isEqualTo(2);
    }

    /**
     * 접수 Consumer 시절에는 이 상황(마감 전 도착, 마감 뒤 처리)에서 마감된 공모주에 PENDING이 생겼다 (#115). 접수 Outbox에서는 생기지 않는다.
     */
    @Test
    @DisplayName("API: 공모주가 이미 CLOSED면 도착 시각이 마감 전이어도 409로 거절하고 아무것도 저장하지 않는다")
    void acceptApi_rejectsWithoutSaving_whenIpoAlreadyClosed() {
        // given: 마감 시각(지금 - 10초)이 지나 CLOSED로 바뀐 공모주, 마감 전(지금 - 60초)에 게이트웨이에 도착한 요청
        jdbcTemplate.update("UPDATE ipo SET status = 'CLOSED', subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);

        // when, then
        assertThatThrownBy(() -> accept(userId, receivedAt, command(UUID.randomUUID(), 100)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SubscriptionErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(subscriptionCount()).isZero();
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("API: 증거금률이 100%가 아닌 공모주는 데이터 오류(IllegalStateException, 500)이고 아무것도 저장하지 않는다")
    void acceptApi_throwsIllegalState_whenMarginRateIsNotFull() {
        // given
        jdbcTemplate.update("UPDATE ipo SET margin_rate = 0.50 WHERE ipo_id = ?", ipoId);

        // when, then
        assertThatThrownBy(() -> accept(userId, receivedAt, command(UUID.randomUUID(), 100)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subscriptionCount()).isZero();
    }

    @Test
    @DisplayName("API: 없는 공모주는 404 RESOURCE_NOT_FOUND")
    void acceptApi_throwsNotFound_whenIpoDoesNotExist() {
        // when, then
        assertThatThrownBy(() -> accept(userId, receivedAt,
                new SubscribeCommand(UUID.randomUUID(), UUID.randomUUID(), accountId, 100)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("API: 마감이 공모주 행을 잡고 CLOSED로 바꾸는 중이면, 접수는 마감 커밋을 기다렸다가 CLOSED를 읽고 거절된다")
    void acceptApi_waitsForClosingTransaction_andIsRejected() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection closer = dataSource.getConnection()) {
            // given: 마감 트랜잭션이 공모주 행을 FOR UPDATE로 잡고 CLOSED로 바꾼 뒤 아직 커밋하지 않음
            closer.setAutoCommit(false);
            try (PreparedStatement lock = closer.prepareStatement("SELECT ipo_id FROM ipo WHERE ipo_id = ? FOR UPDATE");
                 PreparedStatement close = closer.prepareStatement("UPDATE ipo SET status = 'CLOSED' WHERE ipo_id = ?")) {
                lock.setObject(1, ipoId);
                lock.executeQuery().close();
                close.setObject(1, ipoId);
                close.executeUpdate();
            }

            // when: 접수는 공모주 행 잠금을 기다림
            Future<AcceptResult> accepting = executor.submit(() -> accept(userId, receivedAt, command(UUID.randomUUID(), 100)));
            Thread.sleep(1000);
            assertThat(accepting).isNotDone();
            closer.commit();

            // then: 커밋된 CLOSED를 읽고 거절, 아무것도 저장하지 않음
            assertThatThrownBy(() -> accepting.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e.getCause()).getErrorCode())
                    .isEqualTo(SubscriptionErrorCode.INVALID_STATUS_TRANSITION);
            assertThat(subscriptionCount()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("API: 마감 시각 뒤에 도착한 요청은 공모주 행을 잡지 않아, 마감이 행을 잡고 있어도 기다리지 않고 바로 거절된다")
    void acceptApi_rejectsLateRequestWithoutWaitingForIpoRow() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        jdbcTemplate.update("UPDATE ipo SET subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);
        try (Connection closer = dataSource.getConnection()) {
            // given: 마감 트랜잭션이 공모주 행을 FOR UPDATE로 잡고 있음
            closer.setAutoCommit(false);
            try (PreparedStatement lock = closer.prepareStatement("SELECT ipo_id FROM ipo WHERE ipo_id = ? FOR UPDATE")) {
                lock.setObject(1, ipoId);
                lock.executeQuery().close();
            }

            // when: 마감 시각 뒤(지금)에 도착한 요청
            Future<AcceptResult> late = executor.submit(() -> accept(userId, Instant.now(), command(UUID.randomUUID(), 100)));

            // then: 행 잠금을 기다리지 않고 422로 바로 거절됨
            assertThatThrownBy(() -> late.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e.getCause()).getErrorCode())
                    .isEqualTo(SubscriptionErrorCode.SUBSCRIPTION_CLOSED);
            closer.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("API: 접수 트랜잭션이 끝나기 전에 마감하면, 마감은 접수 커밋을 기다렸다가 그 PENDING을 세어 마감하지 않는다")
    void close_waitsForAcceptingTransaction_andCountsItsPending() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        jdbcTemplate.update("UPDATE ipo SET subscription_end_at = now() - interval '10 seconds' WHERE ipo_id = ?", ipoId);
        try {
            // given: 접수가 끝났지만 트랜잭션은 아직 커밋하지 않음 (바깥 트랜잭션에 참여해 커밋을 미룸)
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            Future<AcceptResult> accepting = executor.submit(() -> transaction.execute(status -> {
                AcceptResult result = accept(userId, receivedAt, command(UUID.randomUUID(), 100));
                accepted.countDown();
                awaitQuietly(release);
                return result;
            }));
            assertThat(accepted.await(10, TimeUnit.SECONDS)).isTrue();

            // when: 마감은 공모주 행 잠금을 기다림
            Future<IpoCloseResult> closing = executor.submit(() -> closeService.close(ipoId));
            Thread.sleep(1000);
            assertThat(closing).isNotDone();
            release.countDown();

            // then: 접수가 커밋한 PENDING을 세어 마감하지 않음
            assertThat(accepting.get(10, TimeUnit.SECONDS)).isEqualTo(AcceptResult.ACCEPTED);
            IpoCloseResult result = closing.get(10, TimeUnit.SECONDS);
            assertThat(result.outcome()).isEqualTo(IpoCloseResult.Outcome.IN_PROGRESS_REMAINING);
            assertThat(result.pendingCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM ipo WHERE ipo_id = ?", String.class, ipoId)).isEqualTo("OPEN");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // 청약 API(SubscriptionService)처럼 회원 ID와 requestId로 청약 ID를 계산해 넘긴다
    private AcceptResult accept(UUID user, Instant at, SubscribeCommand command) {
        return acceptService.accept(SubscriptionIdGenerator.generate(user, command.requestId()), user, at, command);
    }

    private SubscribeCommand command(UUID requestId, long requestedQuantity) {
        return new SubscribeCommand(requestId, ipoId, accountId, requestedQuantity);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int subscriptionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscription WHERE ipo_id = ?", Integer.class, ipoId);
    }

    private int outboxCount() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM outbox
                WHERE topic = ? AND aggregate_id IN (SELECT subscription_id FROM subscription WHERE ipo_id = ?)""",
                Integer.class, RESERVE_TOPIC, ipoId);
    }
}
