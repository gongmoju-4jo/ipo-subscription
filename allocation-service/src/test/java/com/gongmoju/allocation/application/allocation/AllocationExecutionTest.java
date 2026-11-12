package com.gongmoju.allocation.application.allocation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.allocation.domain.allocation.AllocationEventPublisher;
import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 배정 실행: 배정 결과 저장, allocation.result/finished Outbox 기록, 회차 DONE (H2 메모리 DB)
// 회차는 DB에 직접 넣고, 확정 청약 목록은 직접 만들어 넘긴다 (ipo.closed 수신, 목록 조회는 다음 이슈)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:allocation-execution;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AllocationExecutionTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final long OFFER_PRICE = 10000;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AllocationEventPublisher allocationEventPublisher;

    @MockitoSpyBean
    private AllocationRepository allocationRepository;

    @Autowired
    private AllocationReplayService allocationReplayService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM allocation.outbox");
        jdbcTemplate.update("DELETE FROM allocation.allocation");
        jdbcTemplate.update("DELETE FROM allocation.allocation_round");
    }

    // 배정 결과를 청약마다 저장하고, 결과마다 allocation.result, 마지막에 allocation.finished를 기록한 뒤 회차를 DONE으로 바꾼다
    @Test
    void savesAllocationsRecordsEventsAndFinishesRound() throws Exception {
        UUID roundId = insertRound("RUNNING", 100, 50, 10);
        List<ConfirmedSubscription> subscriptions = List.of(subscription(100), subscription(200), subscription(300));

        allocationService.allocate(roundId, subscriptions);

        List<Map<String, Object>> allocations = allocations();
        assertThat(allocations).hasSize(3);
        assertThat(allocations).extracting(row -> row.get("SUBSCRIPTION_ID"))
                .containsExactlyInAnyOrderElementsOf(subscriptions.stream().map(ConfirmedSubscription::subscriptionId).toList());
        assertThat(allocations).allSatisfy(row -> {
            assertThat(row.get("ALLOCATION_ROUND_ID")).isEqualTo(roundId);
            assertThat(row.get("IPO_ID")).isEqualTo(IPO_ID);
            assertThat(longValue(row, "ALLOCATED_QUANTITY"))
                    .isEqualTo(longValue(row, "EQUAL_ALLOCATED_QUANTITY") + longValue(row, "PROPORTIONAL_ALLOCATED_QUANTITY"));
            assertThat(longValue(row, "ALLOCATED_AMOUNT")).isEqualTo(longValue(row, "ALLOCATED_QUANTITY") * OFFER_PRICE);
        });
        assertThat(allocations.stream().mapToLong(row -> longValue(row, "ALLOCATED_QUANTITY")).sum()).isEqualTo(100);
        // 재현용 청약 수량: 배정 계산에 넣은 값 그대로 저장
        for (ConfirmedSubscription subscription : subscriptions) {
            Map<String, Object> row = allocations.stream()
                    .filter(r -> r.get("SUBSCRIPTION_ID").equals(subscription.subscriptionId()))
                    .findFirst().orElseThrow();
            assertThat(longValue(row, "REQUESTED_QUANTITY")).isEqualTo(subscription.requestedQuantity());
        }

        // allocation.result: 배정 결과 1건당 1개, 키 accountId, aggregate_id = allocation_id, payload는 저장한 배정 결과와 같다
        List<Map<String, Object>> results = outbox("allocation.result");
        assertThat(results).hasSize(3);
        for (Map<String, Object> allocation : allocations) {
            Map<String, Object> result = results.stream()
                    .filter(row -> row.get("AGGREGATE_ID").equals(allocation.get("ALLOCATION_ID")))
                    .findFirst().orElseThrow();
            assertThat(result.get("AGGREGATE_TYPE")).isEqualTo("ALLOCATION");
            assertThat(result.get("EVENT_TYPE")).isEqualTo("allocation.result");
            assertThat(result.get("MESSAGE_KEY")).isEqualTo(allocation.get("ACCOUNT_ID").toString());
            JsonNode payload = payload(result);
            assertThat(fieldNames(payload)).containsExactlyInAnyOrder("ipoId", "subscriptionId", "accountId",
                    "equalAllocatedQuantity", "proportionalAllocatedQuantity", "allocatedQuantity", "allocatedAmount");
            assertThat(payload.get("ipoId").asText()).isEqualTo(IPO_ID.toString());
            assertThat(payload.get("subscriptionId").asText()).isEqualTo(allocation.get("SUBSCRIPTION_ID").toString());
            assertThat(payload.get("accountId").asText()).isEqualTo(allocation.get("ACCOUNT_ID").toString());
            assertThat(payload.get("equalAllocatedQuantity").asLong()).isEqualTo(longValue(allocation, "EQUAL_ALLOCATED_QUANTITY"));
            assertThat(payload.get("proportionalAllocatedQuantity").asLong()).isEqualTo(longValue(allocation, "PROPORTIONAL_ALLOCATED_QUANTITY"));
            assertThat(payload.get("allocatedQuantity").asLong()).isEqualTo(longValue(allocation, "ALLOCATED_QUANTITY"));
            assertThat(payload.get("allocatedAmount").asLong()).isEqualTo(longValue(allocation, "ALLOCATED_AMOUNT"));
        }

        // allocation.finished: 1개, 키 ipoId, aggregate_id = allocation_round_id
        List<Map<String, Object>> finished = outbox("allocation.finished");
        assertThat(finished).hasSize(1);
        assertThat(finished.get(0).get("AGGREGATE_TYPE")).isEqualTo("ALLOCATION");
        assertThat(finished.get(0).get("AGGREGATE_ID")).isEqualTo(roundId);
        assertThat(finished.get(0).get("MESSAGE_KEY")).isEqualTo(IPO_ID.toString());
        JsonNode payload = payload(finished.get(0));
        assertThat(fieldNames(payload)).containsExactlyInAnyOrder("ipoId", "totalAllocatedQuantity", "subscriptionCount");
        assertThat(payload.get("ipoId").asText()).isEqualTo(IPO_ID.toString());
        assertThat(payload.get("totalAllocatedQuantity").asLong()).isEqualTo(100);
        assertThat(payload.get("subscriptionCount").asLong()).isEqualTo(3);

        assertThat(roundStatus(roundId)).isEqualTo("DONE");
        assertThat(roundFinishedAt(roundId)).isNotNull();
    }

    // 0주를 배정받은 청약도 배정 결과를 저장하고 allocation.result를 기록한다 (계좌 서비스가 증거금을 해제해야 함)
    @Test
    void recordsResultForZeroShareAllocation() {
        // 물량 10주에 20건이 10주씩 청약 → 최소 10건은 0주
        UUID roundId = insertRound("RUNNING", 10, 5, 10);
        List<ConfirmedSubscription> subscriptions = IntStream.range(0, 20).mapToObj(i -> subscription(10)).toList();

        allocationService.allocate(roundId, subscriptions);

        List<Map<String, Object>> allocations = allocations();
        assertThat(allocations).hasSize(20);
        List<Map<String, Object>> zeroShares = allocations.stream()
                .filter(row -> longValue(row, "ALLOCATED_QUANTITY") == 0).toList();
        assertThat(zeroShares).isNotEmpty();
        assertThat(zeroShares).allSatisfy(row -> assertThat(longValue(row, "ALLOCATED_AMOUNT")).isZero());
        assertThat(outbox("allocation.result")).hasSize(20);
    }

    // 확정 청약이 없으면 배정 결과, allocation.result 없이 allocation.finished(합계 0, 건수 0)만 기록하고 DONE
    @Test
    void recordsOnlyFinishedWhenThereAreNoConfirmedSubscriptions() throws Exception {
        UUID roundId = insertRound("RUNNING", 100, 50, 10);

        allocationService.allocate(roundId, List.of());

        assertThat(allocations()).isEmpty();
        assertThat(outbox("allocation.result")).isEmpty();
        List<Map<String, Object>> finished = outbox("allocation.finished");
        assertThat(finished).hasSize(1);
        JsonNode payload = payload(finished.get(0));
        assertThat(payload.get("totalAllocatedQuantity").asLong()).isZero();
        assertThat(payload.get("subscriptionCount").asLong()).isZero();
        assertThat(roundStatus(roundId)).isEqualTo("DONE");
    }

    // 청약 미달이면 신청한 만큼만 배정해서 totalAllocatedQuantity가 일반청약 물량보다 적다
    @Test
    void recordsTotalBelowRetailQuantityWhenUndersubscribed() throws Exception {
        UUID roundId = insertRound("RUNNING", 100, 50, 10);

        allocationService.allocate(roundId, List.of(subscription(10), subscription(20)));

        JsonNode payload = payload(outbox("allocation.finished").get(0));
        assertThat(payload.get("totalAllocatedQuantity").asLong()).isEqualTo(30);
        assertThat(payload.get("subscriptionCount").asLong()).isEqualTo(2);
        assertThat(allocations()).extracting(row -> longValue(row, "ALLOCATED_QUANTITY")).containsExactlyInAnyOrder(10L, 20L);
    }

    // 이미 끝났거나(DONE) 실패한(FAILED) 회차는 다시 배정하지 않고 아무것도 저장하지 않는다
    @Test
    void rejectsRoundThatIsNotRunning() {
        for (String status : List.of("DONE", "FAILED")) {
            setUp();
            UUID roundId = insertRound(status, 100, 50, 10);

            assertThatThrownBy(() -> allocationService.allocate(roundId, List.of(subscription(10))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(status);

            assertThat(allocations()).isEmpty();
            assertThat(outbox("allocation.result")).isEmpty();
            assertThat(outbox("allocation.finished")).isEmpty();
            assertThat(roundStatus(roundId)).isEqualTo(status);
        }
    }

    // 중간에 실패하면 배정 결과, Outbox, 회차 상태가 모두 롤백된다 (allocation.finished 기록에서 실패)
    @Test
    void rollsBackEverythingWhenRecordingFails() {
        UUID roundId = insertRound("RUNNING", 100, 50, 10);
        doThrow(new IllegalStateException("기록 실패"))
                .when(allocationEventPublisher).publishFinished(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> allocationService.allocate(roundId, List.of(subscription(100), subscription(200))))
                .hasMessage("기록 실패");

        assertThat(allocations()).isEmpty();
        assertThat(outbox("allocation.result")).isEmpty();
        assertThat(roundStatus(roundId)).isEqualTo("RUNNING");
        assertThat(roundFinishedAt(roundId)).isNull();
    }

    // 확정 청약이 저장 단위(1만 건)보다 많으면 나눠서 DB로 보내고 비운다. 행 수, 수량 합, Outbox, 회차 DONE, 추첨 재현이 한 번에 저장할 때와 같다
    @Test
    void savesAllocationsInChunksWhenSubscriptionsExceedChunkSize() throws Exception {
        int count = AllocationService.SAVE_CHUNK_SIZE * 2 + 5_000;
        UUID roundId = insertRound("RUNNING", 50_000, 25_000, 10);
        List<ConfirmedSubscription> subscriptions = IntStream.range(0, count).mapToObj(i -> subscription(10 + i % 50)).toList();

        allocationService.allocate(roundId, subscriptions);

        // 묶음 3개 (1만, 1만, 5천)
        verify(allocationRepository, times(3)).flushAndClear();
        assertThat(countRows("SELECT COUNT(*) FROM allocation.allocation WHERE allocation_round_id = ?", roundId)).isEqualTo(count);
        assertThat(countRows("SELECT COALESCE(SUM(allocated_quantity), 0) FROM allocation.allocation WHERE allocation_round_id = ?", roundId))
                .isEqualTo(50_000);
        assertThat(countRows("SELECT COUNT(*) FROM allocation.outbox WHERE topic = ?", "allocation.result")).isEqualTo(count);
        // 청약마다 allocation.result가 하나씩 (빠지거나 겹치지 않음)
        assertThat(countRows("SELECT COUNT(DISTINCT aggregate_id) FROM allocation.outbox WHERE topic = ?", "allocation.result")).isEqualTo(count);
        JsonNode finished = payload(outbox("allocation.finished").get(0));
        assertThat(finished.get("totalAllocatedQuantity").asLong()).isEqualTo(50_000);
        assertThat(finished.get("subscriptionCount").asLong()).isEqualTo(count);
        assertThat(roundStatus(roundId)).isEqualTo("DONE");
        assertThat(roundFinishedAt(roundId)).isNotNull();

        AllocationReplayResult replay = allocationReplayService.replay(IPO_ID);
        assertThat(replay.matched()).isTrue();
        assertThat(replay.comparedCount()).isEqualTo(count);
    }

    // 두 번째 묶음에서 실패하면 이미 DB로 보낸 첫 묶음까지 모두 롤백되고 회차는 RUNNING으로 남는다
    @Test
    void rollsBackEarlierChunksWhenLaterChunkFails() {
        int count = AllocationService.SAVE_CHUNK_SIZE * 2;
        UUID roundId = insertRound("RUNNING", 50_000, 25_000, 10);
        List<ConfirmedSubscription> subscriptions = IntStream.range(0, count).mapToObj(i -> subscription(10 + i % 50)).toList();
        AtomicInteger published = new AtomicInteger();
        doAnswer(invocation -> {
            if (published.incrementAndGet() == AllocationService.SAVE_CHUNK_SIZE + 1) {
                throw new IllegalStateException("두 번째 묶음 기록 실패");
            }
            return invocation.callRealMethod();
        }).when(allocationEventPublisher).publishResult(any());

        assertThatThrownBy(() -> allocationService.allocate(roundId, subscriptions)).hasMessage("두 번째 묶음 기록 실패");

        // 첫 묶음은 flushAndClear로 DB에 보냈지만 커밋 전이라 함께 롤백된다
        verify(allocationRepository, times(1)).flushAndClear();
        assertThat(countRows("SELECT COUNT(*) FROM allocation.allocation WHERE allocation_round_id = ?", roundId)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM allocation.outbox WHERE topic = ?", "allocation.result")).isZero();
        assertThat(outbox("allocation.finished")).isEmpty();
        assertThat(roundStatus(roundId)).isEqualTo("RUNNING");
        assertThat(roundFinishedAt(roundId)).isNull();
    }

    private long countRows(String sql, Object arg) {
        return jdbcTemplate.queryForObject(sql, Long.class, arg);
    }

    private UUID insertRound(String status, long retailQuantity, long equalQuantity, long minQuantity) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO allocation.allocation_round (allocation_round_id, ipo_id, status, offer_price, retail_quantity,"
                        + " equal_quantity, min_quantity, lottery_seed, started_at, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 42, ?, ?, 'SYSTEM:test')",
                id, IPO_ID, status, OFFER_PRICE, retailQuantity, equalQuantity, minQuantity,
                OffsetDateTime.parse("2026-11-04T16:01:00+09:00"), OffsetDateTime.parse("2026-11-04T16:01:00+09:00"));
        return id;
    }

    private ConfirmedSubscription subscription(long requestedQuantity) {
        return new ConfirmedSubscription(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), requestedQuantity);
    }

    private List<Map<String, Object>> allocations() {
        return jdbcTemplate.queryForList("SELECT * FROM allocation.allocation");
    }

    private List<Map<String, Object>> outbox(String topic) {
        return jdbcTemplate.queryForList("SELECT * FROM allocation.outbox WHERE topic = ?", topic);
    }

    private String roundStatus(UUID roundId) {
        return jdbcTemplate.queryForObject("SELECT status FROM allocation.allocation_round WHERE allocation_round_id = ?", String.class, roundId);
    }

    private Object roundFinishedAt(UUID roundId) {
        return jdbcTemplate.queryForObject("SELECT finished_at FROM allocation.allocation_round WHERE allocation_round_id = ?", Object.class, roundId);
    }

    private JsonNode payload(Map<String, Object> outboxRow) throws Exception {
        Object value = outboxRow.get("PAYLOAD");
        String json = value instanceof byte[] bytes ? new String(bytes) : value.toString();
        return objectMapper.readTree(json);
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private long longValue(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
