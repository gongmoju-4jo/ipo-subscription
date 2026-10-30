package com.gongmoju.allocation.presentation.allocation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배정 결과 조회 API (API 명세 3.5). 배정 결과는 DB에 직접 넣는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:allocation-result-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AllocationResultApiTest {

    private static final String ALLOCATIONS = "/api/v1/allocations/subscriptions/";
    private static final String USER_ID_HEADER = "X-User-Id";

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000002");
    private static final UUID SUBSCRIPTION_ID = UUID.fromString("74b6514c-c1eb-3837-968b-8425f63cb692");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID roundId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM allocation.allocation");
        jdbcTemplate.update("DELETE FROM allocation.allocation_round");
        roundId = insertDoneRound(IPO_ID);
    }

    // 본인 청약의 배정 결과를 명세 형식으로 돌려준다 (status 필드 없음)
    @Test
    void returnsOwnAllocationInSpecFormat() throws Exception {
        insertAllocation(SUBSCRIPTION_ID, USER_ID, 1, 2, 45000);

        getAllocation(SUBSCRIPTION_ID, USER_ID.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("배정 결과를 조회했습니다."))
                .andExpect(jsonPath("$.data.ipoId").value(IPO_ID.toString()))
                .andExpect(jsonPath("$.data.subscriptionId").value(SUBSCRIPTION_ID.toString()))
                .andExpect(jsonPath("$.data.equalAllocatedQuantity").value(1))
                .andExpect(jsonPath("$.data.proportionalAllocatedQuantity").value(2))
                .andExpect(jsonPath("$.data.allocatedQuantity").value(3))
                .andExpect(jsonPath("$.data.allocatedAmount").value(45000))
                .andExpect(jsonPath("$.data.status").doesNotExist());
    }

    // 0주 배정도 결과로 돌려준다
    @Test
    void returnsZeroShareAllocation() throws Exception {
        insertAllocation(SUBSCRIPTION_ID, USER_ID, 0, 0, 0);

        getAllocation(SUBSCRIPTION_ID, USER_ID.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocatedQuantity").value(0))
                .andExpect(jsonPath("$.data.allocatedAmount").value(0));
    }

    // 남의 청약은 존재를 숨기고 404다
    @Test
    void hidesOtherUsersAllocationAsNotFound() throws Exception {
        insertAllocation(SUBSCRIPTION_ID, OTHER_USER_ID, 1, 2, 45000);

        expectNotFound(getAllocation(SUBSCRIPTION_ID, USER_ID.toString()));
    }

    // 배정 결과가 없는 청약(배정 전, 배정 대상 아님)은 404다
    @Test
    void returnsNotFoundWhenAllocationDoesNotExist() throws Exception {
        expectNotFound(getAllocation(SUBSCRIPTION_ID, USER_ID.toString()));
    }

    // subscriptionId가 UUID가 아니면 400으로 subscriptionId를 알려준다
    @Test
    void returnsBadRequestWhenSubscriptionIdIsNotUuid() throws Exception {
        mockMvc.perform(get(ALLOCATIONS + "abc").header(USER_ID_HEADER, USER_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("subscriptionId"));
    }

    // 회원 ID 헤더가 없거나 UUID가 아니면 401이다
    @Test
    void returnsUnauthorizedWhenUserIdHeaderIsMissingOrInvalid() throws Exception {
        insertAllocation(SUBSCRIPTION_ID, USER_ID, 1, 2, 45000);

        mockMvc.perform(get(ALLOCATIONS + SUBSCRIPTION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        getAllocation(SUBSCRIPTION_ID, "abc")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private ResultActions getAllocation(UUID subscriptionId, String userIdHeader) throws Exception {
        return mockMvc.perform(get(ALLOCATIONS + subscriptionId).header(USER_ID_HEADER, userIdHeader));
    }

    private void expectNotFound(ResultActions result) throws Exception {
        result.andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("배정 결과를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("subscriptionId"));
    }

    // 값은 API 명세 예시(테스트 공모주 A)와 같다
    private UUID insertDoneRound(UUID ipoId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO allocation.allocation_round (allocation_round_id, ipo_id, status, offer_price, retail_quantity,"
                        + " equal_quantity, min_quantity, lottery_seed, started_at, finished_at, created_at, created_by)"
                        + " VALUES (?, ?, 'DONE', 15000, 100000, 50000, 10, 42, ?, ?, ?, 'SYSTEM:test')",
                id, ipoId, OffsetDateTime.parse("2026-11-04T16:01:00+09:00"), OffsetDateTime.parse("2026-11-04T16:02:00+09:00"),
                OffsetDateTime.parse("2026-11-04T16:01:00+09:00"));
        return id;
    }

    private void insertAllocation(UUID subscriptionId, UUID userId, long equal, long proportional, long amount) {
        jdbcTemplate.update(
                "INSERT INTO allocation.allocation (allocation_id, allocation_round_id, ipo_id, subscription_id, user_id, account_id,"
                        + " equal_allocated_quantity, proportional_allocated_quantity, allocated_quantity, allocated_amount,"
                        + " created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SYSTEM:test')",
                UUID.randomUUID(), roundId, IPO_ID, subscriptionId, userId, UUID.randomUUID(),
                equal, proportional, equal + proportional, amount, OffsetDateTime.parse("2026-11-04T16:02:00+09:00"));
    }
}
