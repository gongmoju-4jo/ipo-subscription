package com.gongmoju.subscription.infrastructure.persistence;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * 부분 UNIQUE({@code uk_subscription_active})와 {@code ON CONFLICT}는 H2에서 지원되지 않으므로 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
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

    /**
     * 공모주 생성 메서드가 없으므로(시드 데이터만 사용) SQL로 직접 저장함.
     */
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
    @DisplayName("처음 보는 requestId의 청약은 저장되고 true를 반환하며 PENDING, 요청 횟수 1, 마지막 요청 시각과 함께 남는다")
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
        assertThat(saved.getAttempts()).isEqualTo(1);
        assertThat(saved.getLastRequestedAt()).isNotNull();
        assertThat(saved.getMarginAmount()).isEqualTo(1_500_000);
        assertThat(saved.getReceivedAt()).isEqualTo(FIRST_RECEIVED_AT);
        // 재전송 내용 비교가 트랜잭션 밖에서도 종목 ID를 비교할 수 있어야 함 (공모주는 `LAZY`)
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
        // 증거금 확보 요청을 보내지 않으므로 요청 횟수 0, 마지막 요청 시각 없음
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getLastRequestedAt()).isNull();
    }

    @Test
    @DisplayName("같은 회원이 같은 종목에 다른 requestId로 유효한 청약을 또 저장하면 예외 없이 저장하지 않고 false를 반환한다 (uk_subscription_active 충돌)")
    void insertIfAbsent_returnsFalse_whenActiveSubscriptionExists() {
        // given
        insert(pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT));
        Subscription second = pending(UUID.randomUUID(), userId, LATER_RECEIVED_AT);

        // when
        boolean inserted = insert(second);

        // then
        assertThat(inserted).isFalse();
        assertThat(count("WHERE ipo_id = ? AND user_id = ?", ipoId, userId)).isEqualTo(1);
        assertThat(subscriptionRepository.findByRequestId(second.getRequestId())).isEmpty();
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
    @DisplayName("같은 회원이 같은 종목에 서로 다른 requestId로 동시에 저장하면 한 건만 저장되고 나머지는 예외 없이 false를 반환한다")
    void insertIfAbsent_savesOnlyOne_whenSameUserInsertsConcurrently() throws Exception {
        // given
        Subscription first = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        Subscription second = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);

        // when
        List<Object> outcomes = insertConcurrently(first, second);

        // then: 늦은 쪽은 앞 저장의 커밋을 기다린 뒤 충돌로 0행
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(count("WHERE ipo_id = ? AND user_id = ?", ipoId, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 저장에서 false를 받은 요청은 같은 requestId가 없고 유효한 청약이 있다고 판정되어 REJECTED로 저장된다 (접수의 충돌 가리기 흐름)")
    void insertIfAbsent_savesRejected_afterLosingConcurrentInsert() throws Exception {
        // given
        Subscription first = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        Subscription second = pending(UUID.randomUUID(), userId, FIRST_RECEIVED_AT);
        List<Object> outcomes = insertConcurrently(first, second);
        Subscription loser = Boolean.FALSE.equals(outcomes.get(0)) ? first : second;

        // when
        boolean sameRequestExists = subscriptionRepository.findByRequestId(loser.getRequestId()).isPresent();
        boolean activeExists = subscriptionRepository.existsActiveByIpoIdAndUserId(ipoId, userId);
        boolean rejectedInserted = insert(Subscription.reject(loser.getId(), loser.getRequestId(), ipo, userId,
                loser.getAccountId(), loser.getRequestedQuantity(), loser.getMarginAmount(), FIRST_RECEIVED_AT,
                RejectReason.ALREADY_SUBSCRIBED));

        // then
        assertThat(sameRequestExists).isFalse();
        assertThat(activeExists).isTrue();
        assertThat(rejectedInserted).isTrue();
        assertThat(count("WHERE ipo_id = ? AND user_id = ? AND status = 'PENDING'", ipoId, userId)).isEqualTo(1);
        assertThat(count("WHERE ipo_id = ? AND user_id = ? AND status = 'REJECTED'", ipoId, userId)).isEqualTo(1);
        assertThat(subscriptionRepository.findByRequestId(loser.getRequestId()).orElseThrow().getRejectReason())
                .isEqualTo(RejectReason.ALREADY_SUBSCRIBED);
    }

    @Test
    @DisplayName("같은 requestId를 동시에 저장하면 한 건만 저장되고 나머지는 예외 없이 false를 반환한다")
    void insertIfAbsent_savesOnlyOne_whenSameRequestIdInsertsConcurrently() throws Exception {
        // given
        UUID requestId = UUID.randomUUID();
        Subscription first = pending(requestId, userId, FIRST_RECEIVED_AT);
        Subscription second = pending(requestId, userId, LATER_RECEIVED_AT);

        // when
        List<Object> outcomes = insertConcurrently(first, second);

        // then: request_id, 부분 UNIQUE 중 어느 쪽으로 먼저 감지돼도 대상 없는 ON CONFLICT라 0행
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(count("WHERE request_id = ?", requestId)).isEqualTo(1);
    }

    @Test
    @DisplayName("subscription 테이블의 유니크 제약은 청약 ID, request_id, 유효 청약(ipo_id, user_id) 세 개뿐이다 (insertIfAbsent의 대상 없는 ON CONFLICT가 이 셋만 전제함)")
    void subscriptionTable_hasOnlyTheUniqueConstraintsThatInsertIfAbsentAssumes() {
        // when: 이 테이블의 유니크 인덱스마다 정의를 읽음 (PK, UNIQUE 제약, schema.sql의 부분 유니크 인덱스 모두 유니크 인덱스로 보임)
        List<String> definitions = jdbcTemplate.queryForList("""
                SELECT pg_get_indexdef(x.indexrelid)
                FROM pg_index x
                JOIN pg_class t ON t.oid = x.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE t.relname = 'subscription' AND n.nspname = current_schema() AND x.indisunique""", String.class);

        // then: 셋 외의 유니크 제약을 더하면, 접수가 0행을 같은 requestId 또는 이중청약으로 가리는 판단(SubscriptionAcceptService)을 다시 봐야 함
        assertThat(definitions).hasSize(3);
        assertThat(definitions).anySatisfy(d -> assertThat(d).contains("(subscription_id)"));
        assertThat(definitions).anySatisfy(d -> assertThat(d).contains("(request_id)"));
        assertThat(definitions).anySatisfy(d -> assertThat(d).contains("uk_subscription_active").contains("(ipo_id, user_id)"));
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

    /**
     * 두 스레드가 {@code CyclicBarrier}로 같은 시점에 출발하여 각자의 트랜잭션으로 저장함. 결과는 저장 여부({@code Boolean}) 또는 던져진 예외({@code Throwable})이며 순서는 제출 순서와 같음.
     */
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
