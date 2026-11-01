package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
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

// 부분 UNIQUE(uk_subscription_active)와 ON CONFLICT는 H2에서 동작하지 않아서 실제 PostgreSQL(Testcontainers)로 확인한다. Docker가 필요하다
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("청약 저장소 (PostgreSQL)")
class SubscriptionRepositoryTest {

    private static final Instant FIRST_RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");
    private static final Instant LATER_RECEIVED_AT = Instant.parse("2026-10-27T06:59:59.456Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private IpoJpaRepository ipoJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID ipoId;
    private Ipo ipo;
    private UUID userId;

    // 공모주 생성 메서드가 없어서(시드 데이터만 사용) SQL로 넣는다
    @BeforeEach
    void setUp() {
        ipoId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,
                                 margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version,
                                 created_at, created_by, updated_at, updated_by)
                VALUES (?, '테스트종목', 15000, 100000, 50000, 10, 1000, 1.00,
                        now() - interval '1 day', now() + interval '1 day', current_date + 5, 'OPEN', 0,
                        now(), 'test', now(), 'test')""", ipoId);
        ipo = ipoJpaRepository.findById(ipoId).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM subscription WHERE ipo_id = ?", ipoId);
        jdbcTemplate.update("DELETE FROM ipo WHERE ipo_id = ?", ipoId);
    }

    @Test
    @DisplayName("처음 보는 requestId의 청약은 저장되고 true를 반환하며 PENDING, 시도 횟수 0으로 남는다")
    void insertIfAbsent_savesAndReturnsTrue_whenRequestIdIsNew() {
        // given
        Subscription subscription = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);

        // when
        boolean inserted = insert(subscription);

        // then
        assertThat(inserted).isTrue();
        Subscription saved = subscriptionRepository.findByRequestId(subscription.getRequestId()).orElseThrow();
        assertThat(saved.getId()).isEqualTo(subscription.getId());
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(saved.getRejectReason()).isNull();
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getMarginAmount()).isEqualTo(1_500_000);
        assertThat(saved.getReceivedAt()).isEqualTo(FIRST_RECEIVED_AT);
        // 검증 5가 트랜잭션 밖에서도 종목 ID를 비교할 수 있어야 한다 (공모주는 LAZY)
        assertThat(saved.getIpo().getId()).isEqualTo(ipoId);
    }

    @Test
    @DisplayName("같은 requestId로 다시 저장하면 false를 반환하고 처음 접수된 receivedAt이 유지된다")
    void insertIfAbsent_returnsFalseAndKeepsFirstReceivedAt_whenRequestIdExists() {
        // given
        UUID requestId = UUID.randomUUID();
        Subscription first = pending(requestId, userId, FIRST_RECEIVED_AT);
        Subscription resent = pending(requestId, userId, LATER_RECEIVED_AT);
        insert(first);

        // when
        boolean inserted = insert(resent);

        // then
        assertThat(inserted).isFalse();
        Subscription saved = subscriptionRepository.findByRequestId(requestId).orElseThrow();
        assertThat(saved.getReceivedAt()).isEqualTo(FIRST_RECEIVED_AT);
        assertThat(count("WHERE request_id = ?", requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("네이티브 INSERT도 감사 필드와 버전을 채운다 (created_by는 요청이 아니므로 SYSTEM:subscription-service)")
    void insertIfAbsent_fillsAuditFieldsAndVersion() {
        // given
        Subscription subscription = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);

        // when
        insert(subscription);

        // then
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT created_at, created_by, updated_at, updated_by, version FROM subscription WHERE request_id = ?",
                subscription.getRequestId());
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("created_by")).isEqualTo("SYSTEM:subscription-service");
        assertThat(row.get("updated_by")).isEqualTo("SYSTEM:subscription-service");
        assertThat(((Number) row.get("version")).longValue()).isZero();
    }

    @Test
    @DisplayName("REJECTED 청약은 거절 사유와 함께 저장된다")
    void insertIfAbsent_savesRejectReason_whenRejected() {
        // given
        Subscription rejected = rejected(UUID.randomUUID(), userId);

        // when
        boolean inserted = insert(rejected);

        // then
        assertThat(inserted).isTrue();
        Subscription saved = subscriptionRepository.findByRequestId(rejected.getRequestId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.REJECTED);
        assertThat(saved.getRejectReason()).isEqualTo(RejectReason.ALREADY_SUBSCRIBED);
    }

    @Test
    @DisplayName("같은 회원이 같은 종목에 다른 requestId로 유효한 청약을 또 저장하면 uk_subscription_active 위반이 예외로 올라온다")
    void insertIfAbsent_throwsDataIntegrityViolation_whenActiveSubscriptionExists() {
        // given
        insert(pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT));
        Subscription second = pending(UUID.randomUUID(), userId, LATER_RECEIVED_AT);

        // when, then
        assertThatThrownBy(() -> insert(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasCauseInstanceOf(ConstraintViolationException.class)
                // 다른 무결성 위반(NOT NULL, FK)과 구분해서 재시도 대상으로 분류할 수 있는지 확인
                .satisfies(e -> assertThat(((ConstraintViolationException) e.getCause()).getConstraintName())
                        .isEqualTo("uk_subscription_active"));
        assertThat(count("WHERE ipo_id = ? AND user_id = ?", ipoId, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("유효한 청약이 있어도 REJECTED 청약은 같은 회원, 같은 종목에 함께 저장된다")
    void insertIfAbsent_allowsRejected_alongsideActiveSubscription() {
        // given
        insert(pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT));
        Subscription rejected = rejected(UUID.randomUUID(), userId);

        // when
        boolean inserted = insert(rejected);

        // then
        assertThat(inserted).isTrue();
        assertThat(count("WHERE ipo_id = ? AND user_id = ?", ipoId, userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 회원이 같은 종목에 서로 다른 requestId로 동시에 저장하면 한 건만 저장되고 나머지는 uk_subscription_active 위반이 난다")
    void insertIfAbsent_savesOnlyOne_whenSameUserInsertsConcurrently() throws Exception {
        // given
        Subscription first = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        Subscription second = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);

        // when
        List<Object> outcomes = insertConcurrently(first, second);

        // then
        assertThat(outcomes).filteredOn(Boolean.TRUE::equals).hasSize(1);
        assertThat(outcomes).filteredOn(Throwable.class::isInstance).hasSize(1)
                .allSatisfy(e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class));
        assertThat(count("WHERE ipo_id = ? AND user_id = ?", ipoId, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 저장에서 진 요청을 다시 처리하면 유효한 청약이 이미 있다고 판정되어 REJECTED로 저장된다 (재시도 흐름)")
    void insertIfAbsent_savesRejected_whenRetriedAfterConcurrentViolation() throws Exception {
        // given
        Subscription first = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        Subscription second = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        List<Object> outcomes = insertConcurrently(first, second);
        Subscription loser = outcomes.get(0) instanceof Throwable ? first : second;

        // when
        boolean activeExists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);
        boolean rejectedInserted = insert(Subscription.reject(loser.getId(), loser.getRequestId(), ipo, userId,
                loser.getAccountId(), loser.getRequestedQuantity(), loser.getMarginAmount(), FIRST_RECEIVED_AT,
                RejectReason.ALREADY_SUBSCRIBED));

        // then
        assertThat(activeExists).isTrue();
        assertThat(rejectedInserted).isTrue();
        assertThat(count("WHERE ipo_id = ? AND user_id = ? AND status = 'PENDING'", ipoId, userId)).isEqualTo(1);
        assertThat(count("WHERE ipo_id = ? AND user_id = ? AND status = 'REJECTED'", ipoId, userId)).isEqualTo(1);
        assertThat(subscriptionRepository.findByRequestId(loser.getRequestId()).orElseThrow().getRejectReason())
                .isEqualTo(RejectReason.ALREADY_SUBSCRIBED);
    }

    @Test
    @DisplayName("같은 requestId를 동시에 저장하면 한 건만 저장되고 나머지는 예외 없이 false를 반환한다")
    void insertIfAbsent_savesOnlyOneWithoutException_whenSameRequestIdInsertsConcurrently() throws Exception {
        // given
        UUID requestId = UUID.randomUUID();
        Subscription first = pending(requestId, userId, FIRST_RECEIVED_AT);
        Subscription second = pending(requestId, userId, LATER_RECEIVED_AT);

        // when
        List<Object> outcomes = insertConcurrently(first, second);

        // then
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(count("WHERE request_id = ?", requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 청약이 있으면 유효한 청약이 있는 것으로 본다")
    void existsActive_returnsTrue_whenPendingSubscriptionExists() {
        // given
        insert(pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT));

        // when
        boolean exists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);

        // then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("REJECTED 청약만 있으면 유효한 청약이 없는 것으로 본다 (재청약 가능)")
    void existsActive_returnsFalse_whenOnlyRejectedSubscriptionExists() {
        // given
        insert(rejected(UUID.randomUUID(), userId));

        // when
        boolean exists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);

        // then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("CANCELLED 청약만 있으면 유효한 청약이 없는 것으로 본다 (재청약 가능)")
    void existsActive_returnsFalse_whenOnlyCancelledSubscriptionExists() {
        // given
        Subscription subscription = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        insert(subscription);
        jdbcTemplate.update("UPDATE subscription SET status = 'CANCELLED', cancel_reason = 'USER_CANCEL' WHERE request_id = ?",
                subscription.getRequestId());

        // when
        boolean exists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);

        // then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("다른 회원의 청약이나 다른 종목의 청약은 유효한 청약으로 보지 않는다")
    void existsActive_returnsFalse_whenSubscriptionBelongsToOtherUser() {
        // given
        insert(pending(UUID.randomUUID(), UUID.randomUUID(), FIRST_RECEIVED_AT));

        // when
        boolean exists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);

        // then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("저장되지 않은 requestId로 조회하면 빈 값이다")
    void findByRequestId_returnsEmpty_whenNotSaved() {
        // given

        // when, then
        assertThat(subscriptionRepository.findByRequestId(UUID.randomUUID())).isEmpty();
    }

    private boolean insert(Subscription subscription) {
        return transactionTemplate.execute(status -> subscriptionRepository.insertIfAbsent(subscription));
    }

    // 두 스레드가 같은 시점에 각자의 트랜잭션으로 저장한다. 결과는 저장 여부(Boolean) 또는 던져진 예외(Throwable), 순서는 제출 순서와 같다
    private List<Object> insertConcurrently(Subscription first, Subscription second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<Boolean>> futures = Stream.of(first, second)
                    .map(subscription -> executor.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return insert(subscription);
                    }))
                    .toList();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Boolean> future : futures) {
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

    private Subscription pending(UUID requestId, UUID user, Instant receivedAt) {
        return Subscription.accept(UUID.randomUUID(), requestId, ipo, user, UUID.randomUUID(), 100, 1_500_000, receivedAt);
    }

    private Subscription rejected(UUID requestId, UUID user) {
        return Subscription.reject(UUID.randomUUID(), requestId, ipo, user, UUID.randomUUID(), 100, 1_500_000,
                FIRST_RECEIVED_AT, RejectReason.ALREADY_SUBSCRIBED);
    }

    private int count(String where, Object... args) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscription " + where, Integer.class, args);
    }
}
