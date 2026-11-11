package com.gongmoju.allocation.presentation.allocation;

import com.gongmoju.allocation.application.allocation.AllocationService;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 추첨 재현 관리자 API. 배정은 AllocationService로 실행하고 회차는 DB에 직접 넣는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:allocation-replay-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AllocationReplayApiTest {

    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AllocationService allocationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM allocation.outbox");
        jdbcTemplate.update("DELETE FROM allocation.allocation");
        jdbcTemplate.update("DELETE FROM allocation.allocation_round");
    }

    // 배정한 그대로면 일치, 비교 건수와 빈 불일치 목록을 돌려준다
    @Test
    void returnsMatchedReplayResult() throws Exception {
        allocate(20);

        replay(IPO_ID.toString(), "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("추첨 재현 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.ipoId").value(IPO_ID.toString()))
                .andExpect(jsonPath("$.data.matched").value(true))
                .andExpect(jsonPath("$.data.comparedCount").value(20))
                .andExpect(jsonPath("$.data.mismatchedSubscriptionIds").isEmpty());
    }

    // 저장된 결과가 바뀐 청약을 불일치 목록으로 돌려준다
    @Test
    void returnsMismatchedSubscriptions() throws Exception {
        List<ConfirmedSubscription> subscriptions = allocate(20);
        UUID tampered = subscriptions.get(0).subscriptionId();
        jdbcTemplate.update("UPDATE allocation.allocation SET allocated_amount = allocated_amount + 1 WHERE subscription_id = ?",
                tampered);

        replay(IPO_ID.toString(), "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matched").value(false))
                .andExpect(jsonPath("$.data.mismatchedSubscriptionIds.length()").value(1))
                .andExpect(jsonPath("$.data.mismatchedSubscriptionIds[0]").value(tampered.toString()));
    }

    // ADMIN이 아니거나 역할 헤더가 없거나 정의되지 않은 역할이면 403 (서비스 포트 직접 호출 방어)
    @Test
    void returnsForbiddenWhenNotAdmin() throws Exception {
        allocate(5);

        expectError(replay(IPO_ID.toString(), "USER"), 403, "FORBIDDEN");
        expectError(mockMvc.perform(get(path(IPO_ID.toString()))), 403, "FORBIDDEN");
        expectError(replay(IPO_ID.toString(), "SUPER"), 403, "FORBIDDEN");
    }

    // 배정 회차가 없는 공모주는 404
    @Test
    void returnsNotFoundWhenRoundDoesNotExist() throws Exception {
        expectError(replay(IPO_ID.toString(), "ADMIN"), 404, "RESOURCE_NOT_FOUND")
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    // 배정이 끝나지 않은 회차는 422
    @Test
    void returnsUnprocessableWhenRoundIsNotDone() throws Exception {
        insertRound("RUNNING");

        expectError(replay(IPO_ID.toString(), "ADMIN"), 422, "BUSINESS_RULE_VIOLATION");
    }

    // 청약 수량이 저장되지 않은 배정 결과(컬럼 추가 전 데이터)가 있으면 422
    @Test
    void returnsUnprocessableWhenRequestedQuantityIsMissing() throws Exception {
        allocate(5);
        jdbcTemplate.update("UPDATE allocation.allocation SET requested_quantity = NULL");

        expectError(replay(IPO_ID.toString(), "ADMIN"), 422, "BUSINESS_RULE_VIOLATION");
    }

    // ipoId가 UUID가 아니면 400으로 ipoId를 알려준다
    @Test
    void returnsBadRequestWhenIpoIdIsNotUuid() throws Exception {
        expectError(replay("abc", "ADMIN"), 400, "VALIDATION_ERROR")
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    private List<ConfirmedSubscription> allocate(int count) {
        UUID roundId = insertRound("RUNNING");
        List<ConfirmedSubscription> subscriptions = IntStream.range(0, count)
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
                        + " VALUES (?, ?, ?, 15000, 101, 51, 10, 42, ?, ?, 'SYSTEM:test')",
                id, IPO_ID, status,
                OffsetDateTime.parse("2026-11-10T16:01:00+09:00"), OffsetDateTime.parse("2026-11-10T16:01:00+09:00"));
        return id;
    }

    private String path(String ipoId) {
        return "/api/v1/admin/allocations/ipos/" + ipoId + "/replay";
    }

    private ResultActions replay(String ipoId, String role) throws Exception {
        return mockMvc.perform(get(path(ipoId)).header(USER_ROLE_HEADER, role));
    }

    private ResultActions expectError(ResultActions result, int status, String code) throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(code));
    }
}
