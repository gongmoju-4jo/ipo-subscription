package com.gongmoju.subscription.presentation.internal;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공모주별 확정 청약 목록 내부 API (API 명세 4.1). 청약은 청약 API가 저장하므로 공모주와 청약을 DB에 직접 넣는다 (H2 메모리 DB)
// 배정 서비스는 사용자 헤더 없이 호출하므로 요청에 X-User-Id를 넣지 않는다
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:internal-subscription-list-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class InternalSubscriptionListApiTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("9d4e0000-0000-4000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.subscription");
        jdbcTemplate.update("DELETE FROM subscription.ipo");
        insertIpo(IPO_ID, "CLOSED");
    }

    @Test
    void 확정_청약이_없으면_빈_목록을_기본_page_0_size_500으로_응답한다() throws Exception {
        mockMvc.perform(list(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(500))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void 항목은_명세의_6개_필드를_KST_시각으로_담는다() throws Exception {
        UUID subscriptionId = insertSubscription(UUID.randomUUID(), IPO_ID, "CONFIRMED");

        String body = mockMvc.perform(list(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.data.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(100))
                .andExpect(jsonPath("$.data.items[0].marginAmount").value(1500000))
                .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-11-03T10:00:00+09:00"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item = JsonPath.read(body, "$.data.items[0]");
        assertThat(item).containsOnlyKeys("subscriptionId", "userId", "accountId", "requestedQuantity", "marginAmount", "receivedAt");
    }

    @Test
    void 해당_공모주의_CONFIRMED_청약만_돌려준다() throws Exception {
        UUID otherIpo = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000002"), "CLOSED");
        UUID confirmed = insertSubscription(UUID.randomUUID(), IPO_ID, "CONFIRMED");
        for (String other : List.of("PENDING", "REJECTED", "COMPENSATING", "CANCELLED", "ALLOCATED", "REFUNDED")) {
            insertSubscription(UUID.randomUUID(), IPO_ID, other);
        }
        insertSubscription(UUID.randomUUID(), otherIpo, "CONFIRMED");

        mockMvc.perform(list(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(confirmed.toString()));
    }

    // PostgreSQL은 UUID를 바이트 순(부호 없는 비교)으로 정렬한다. Java UUID.compareTo(부호 있는 비교)와 달라서
    // 기대 순서는 문자열 순으로 만든다 (첫 바이트가 0x80 이상인 ID가 뒤에 와야 한다)
    @Test
    void subscriptionId_오름차순이며_페이지_사이_중복과_누락이_없다() throws Exception {
        List<String> ids = List.of(
                "f0000000-0000-3000-8000-000000000001", "10000000-0000-3000-8000-000000000001",
                "90000000-0000-3000-8000-000000000001", "00000000-0000-3000-8000-000000000002",
                "7fffffff-0000-3000-8000-000000000001");
        for (String id : ids) {
            insertSubscription(UUID.fromString(id), IPO_ID, "CONFIRMED");
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = mockMvc.perform(list(IPO_ID).param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.page").value(page))
                    .andExpect(jsonPath("$.data.size").value(2))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.data.items[*].subscriptionId"));
        }

        assertThat(seen).containsExactlyElementsOf(ids.stream().sorted().toList());
    }

    @Test
    void sort_파라미터를_보내도_subscriptionId_오름차순을_유지한다() throws Exception {
        UUID first = insertSubscription(UUID.fromString("10000000-0000-3000-8000-000000000001"), IPO_ID, "CONFIRMED");
        insertSubscription(UUID.fromString("20000000-0000-3000-8000-000000000001"), IPO_ID, "CONFIRMED");

        mockMvc.perform(list(IPO_ID).param("sort", "subscriptionId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(first.toString()));
    }

    @Test
    void size는_최대_1000까지_허용한다() throws Exception {
        mockMvc.perform(list(IPO_ID).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1000));
    }

    @Test
    void status가_없거나_CONFIRMED가_아니면_400이다() throws Exception {
        mockMvc.perform(get("/internal/ipos/" + IPO_ID + "/subscriptions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));

        for (String status : List.of("PENDING", "ALLOCATED", "confirmed", "UNKNOWN")) {
            mockMvc.perform(get("/internal/ipos/" + IPO_ID + "/subscriptions").param("status", status))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("status"));
        }
    }

    @Test
    void page와_size가_범위를_벗어나거나_숫자가_아니면_400이다() throws Exception {
        // page 상한은 Integer.MAX_VALUE / 500 = 4294967 (기본 size 기준)
        List<String[]> invalid = List.of(
                new String[]{"page", "-1"}, new String[]{"page", "abc"}, new String[]{"page", "4294968"},
                new String[]{"size", "0"}, new String[]{"size", "1001"}, new String[]{"size", "abc"});

        for (String[] param : invalid) {
            mockMvc.perform(list(IPO_ID).param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(param[0]));
        }
        mockMvc.perform(list(IPO_ID).param("size", "1001"))
                .andExpect(jsonPath("$.error.message").value("페이지 요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 크기는 1 이상 1000 이하여야 합니다."));
    }

    @Test
    void ipoId가_UUID가_아니면_400이다() throws Exception {
        mockMvc.perform(get("/internal/ipos/not-a-uuid/subscriptions").param("status", "CONFIRMED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    @Test
    void 공모주가_없으면_404다() throws Exception {
        mockMvc.perform(list(UUID.fromString("8b2d0000-0000-4000-8000-000000000099")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    @Test
    void 공모주가_CLOSED가_아니면_409다() throws Exception {
        UUID open = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000003"), "OPEN");
        UUID allocated = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000004"), "ALLOCATED");

        for (UUID ipoId : List.of(open, allocated)) {
            mockMvc.perform(list(ipoId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.error.details[0].field").value("ipoId"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("마감된 공모주가 아닙니다."));
        }
    }

    private MockHttpServletRequestBuilder list(UUID ipoId) {
        return get("/internal/ipos/" + ipoId + "/subscriptions").param("status", "CONFIRMED");
    }

    // 값은 API 명세 4.1 예시와 같다 (100주, 증거금 1,500,000원, 접수 2026-11-03T10:00:00+09:00)
    private UUID insertSubscription(UUID subscriptionId, UUID ipoId, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription.subscription (subscription_id, request_id, ipo_id, user_id, account_id,"
                        + " requested_quantity, margin_amount, status, attempts, received_at, version, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 0, ?, 0, ?, 'SYSTEM:test')",
                subscriptionId, UUID.randomUUID(), ipoId, USER_ID, ACCOUNT_ID, status,
                OffsetDateTime.parse("2026-11-03T10:00:00.123+09:00"), OffsetDateTime.parse("2026-11-03T10:00:01+09:00"));
        return subscriptionId;
    }

    private UUID insertIpo(UUID ipoId, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, '테스트 공모주 A', 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                ipoId, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
        return ipoId;
    }
}
