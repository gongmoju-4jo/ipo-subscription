package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 추첨 재현: 배정을 실행한 뒤 저장된 조건, lotterySeed, 청약 수량으로 다시 계산해 저장된 결과와 비교한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:allocation-replay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AllocationReplayTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000002");
    private static final long OFFER_PRICE = 10000;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private AllocationReplayService allocationReplayService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM allocation.outbox");
        jdbcTemplate.update("DELETE FROM allocation.allocation");
        jdbcTemplate.update("DELETE FROM allocation.allocation_round");
    }

    // 배정한 그대로 다시 계산하면 모든 청약의 결과가 같다 (균등 추첨, 단주가 일어나는 입력)
    @Test
    void replayMatchesStoredAllocations() {
        List<ConfirmedSubscription> subscriptions = allocate();

        AllocationReplayResult result = allocationReplayService.replay(IPO_ID);

        assertThat(result.ipoId()).isEqualTo(IPO_ID);
        assertThat(result.matched()).isTrue();
        assertThat(result.comparedCount()).isEqualTo(subscriptions.size());
        assertThat(result.mismatchedSubscriptionIds()).isEmpty();
    }

    // 저장된 배정 결과가 바뀌면 그 청약을 결과가 다른 청약으로 찾는다
    @Test
    void replayFindsTamperedAllocation() {
        List<ConfirmedSubscription> subscriptions = allocate();
        UUID tampered = subscriptions.get(3).subscriptionId();
        jdbcTemplate.update("UPDATE allocation.allocation SET allocated_quantity = allocated_quantity + 1,"
                + " proportional_allocated_quantity = proportional_allocated_quantity + 1 WHERE subscription_id = ?", tampered);

        AllocationReplayResult result = allocationReplayService.replay(IPO_ID);

        assertThat(result.matched()).isFalse();
        assertThat(result.comparedCount()).isEqualTo(subscriptions.size());
        assertThat(result.mismatchedSubscriptionIds()).containsExactly(tampered);
    }

    // 시드가 다르면 추첨 결과가 달라져 일치하지 않는다 (재현이 lotterySeed에 달려 있음을 확인)
    @Test
    void replayDoesNotMatchWithDifferentSeed() {
        allocate();
        jdbcTemplate.update("UPDATE allocation.allocation_round SET lottery_seed = 43 WHERE ipo_id = ?", IPO_ID);

        AllocationReplayResult result = allocationReplayService.replay(IPO_ID);

        assertThat(result.matched()).isFalse();
        assertThat(result.mismatchedSubscriptionIds()).isNotEmpty();
        assertThat(result.mismatchedSubscriptionIds()).isSortedAccordingTo(Comparator.naturalOrder());
    }

    // 확정 청약이 0건이었던 회차는 비교할 것이 없어 일치로 본다
    @Test
    void replayMatchesRoundWithoutAllocations() {
        UUID roundId = insertRound("RUNNING");
        allocationService.allocate(roundId, List.of());

        AllocationReplayResult result = allocationReplayService.replay(IPO_ID);

        assertThat(result.matched()).isTrue();
        assertThat(result.comparedCount()).isZero();
    }

    // 청약 수량을 저장하기 전에 만든 배정 결과(null)가 있으면 재현할 수 없다
    @Test
    void rejectsReplayWhenRequestedQuantityIsMissing() {
        List<ConfirmedSubscription> subscriptions = allocate();
        jdbcTemplate.update("UPDATE allocation.allocation SET requested_quantity = NULL WHERE subscription_id = ?",
                subscriptions.get(0).subscriptionId());

        assertThatThrownBy(() -> allocationReplayService.replay(IPO_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("청약 수량")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
    }

    // 배정이 끝나지 않은 회차(RUNNING, FAILED)는 재현할 수 없다
    @Test
    void rejectsReplayWhenRoundIsNotDone() {
        for (String status : List.of("RUNNING", "FAILED")) {
            setUp();
            insertRound(status);

            assertThatThrownBy(() -> allocationReplayService.replay(IPO_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining(status)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.BUSINESS_RULE_VIOLATION);
        }
    }

    // 배정 회차가 없는 공모주는 404
    @Test
    void rejectsReplayWhenRoundDoesNotExist() {
        assertThatThrownBy(() -> allocationReplayService.replay(IPO_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    // 물량 1,003주(균등 501주) / 최소 10주 / 청약 60건 (10~69주)
    // 균등 몫 8주 + 나머지 21주를 60건 중 추첨, 비례 502주를 나누고 남은 단주까지 배정한다
    private List<ConfirmedSubscription> allocate() {
        UUID roundId = insertRound("RUNNING");
        List<ConfirmedSubscription> subscriptions = IntStream.range(0, 60)
                .mapToObj(i -> new ConfirmedSubscription(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10 + i))
                .toList();
        allocationService.allocate(roundId, subscriptions);
        return subscriptions;
    }

    private UUID insertRound(String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO allocation.allocation_round (allocation_round_id, ipo_id, status, offer_price, retail_quantity,"
                        + " equal_quantity, min_quantity, lottery_seed, started_at, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, 1003, 501, 10, 42, ?, ?, 'SYSTEM:test')",
                id, IPO_ID, status, OFFER_PRICE,
                OffsetDateTime.parse("2026-11-10T16:01:00+09:00"), OffsetDateTime.parse("2026-11-10T16:01:00+09:00"));
        return id;
    }
}
