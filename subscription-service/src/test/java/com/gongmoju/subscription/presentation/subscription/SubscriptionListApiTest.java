package com.gongmoju.subscription.presentation.subscription;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내 청약 목록 API. 청약은 접수 Consumer가 저장하므로 공모주와 청약을 DB에 직접 넣는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:subscription-list-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SubscriptionListApiTest {

    private static final String SUBSCRIPTIONS = "/api/v1/subscriptions";

    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000099");
    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("9d4e0000-0000-4000-8000-000000000002");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-11-03T10:00:01+09:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.subscription");
        jdbcTemplate.update("DELETE FROM subscription.ipo");
        insertIpo(IPO_ID, "테스트 공모주 A");
    }

    @Test
    void 청약이_없으면_빈_목록을_200으로_응답한다() throws Exception {
        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("청약 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void 항목은_명세의_9개_필드를_종목명과_KST_시각으로_담고_상세_전용_필드는_담지_않는다() throws Exception {
        UUID subscriptionId = insertSubscription(USER_ID, "CONFIRMED", CREATED_AT,
                OffsetDateTime.parse("2026-11-03T10:00:02+09:00"));

        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.data.items[0].ipoId").value(IPO_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].ipoName").value("테스트 공모주 A"))
                .andExpect(jsonPath("$.data.items[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(100))
                .andExpect(jsonPath("$.data.items[0].marginAmount").value(1500000))
                .andExpect(jsonPath("$.data.items[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].confirmedAt").value("2026-11-03T10:00:02+09:00"))
                .andExpect(jsonPath("$.data.items[0].requestId").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].rejectReason").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].refundAmount").doesNotExist());
    }

    @Test
    void 확정_전_청약의_confirmedAt은_null이다() throws Exception {
        insertSubscription(USER_ID, "PENDING", CREATED_AT, null);

        String body = mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"confirmedAt\":null");
    }

    @Test
    void 본인_청약만_돌려준다() throws Exception {
        UUID mine = insertSubscription(USER_ID, "CONFIRMED", CREATED_AT, null);
        insertSubscription(OTHER_USER_ID, "CONFIRMED", CREATED_AT, null);

        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(mine.toString()));
    }

    @Test
    void 거절_취소된_청약도_목록에_포함한다() throws Exception {
        UUID otherIpo = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000002"), "테스트 공모주 B");
        UUID thirdIpo = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000003"), "테스트 공모주 C");
        insertSubscription(UUID.randomUUID(), USER_ID, IPO_ID, "REJECTED", CREATED_AT, null);
        insertSubscription(UUID.randomUUID(), USER_ID, otherIpo, "CANCELLED", CREATED_AT.plusMinutes(1), null);
        insertSubscription(UUID.randomUUID(), USER_ID, thirdIpo, "REFUNDED", CREATED_AT.plusMinutes(2), null);

        mockMvc.perform(list())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items[0].ipoName").value("테스트 공모주 C"))
                .andExpect(jsonPath("$.data.items[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.items[1].ipoName").value("테스트 공모주 B"))
                .andExpect(jsonPath("$.data.items[2].status").value("REJECTED"));
    }

    @Test
    void sort를_보내지_않으면_등록_시각_최신순이고_createdAt_정렬은_그_방향을_따른다() throws Exception {
        UUID earlier = insertSubscription(USER_ID, "CONFIRMED", CREATED_AT, null);
        UUID later = insertSubscription(USER_ID, "CONFIRMED", CREATED_AT.plusSeconds(5), null);

        mockMvc.perform(list())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(later.toString()))
                .andExpect(jsonPath("$.data.items[1].subscriptionId").value(earlier.toString()));
        mockMvc.perform(list().param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(earlier.toString()));
    }

    @Test
    void 등록_시각이_같은_청약은_subscriptionId_순이며_페이지_사이_중복과_누락이_없다() throws Exception {
        List<String> inserted = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            UUID id = UUID.fromString(String.format("e8bd0000-0000-3000-8000-%012d", i));
            inserted.add(insertSubscription(id, USER_ID, IPO_ID, "REJECTED", CREATED_AT, null).toString());
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            String body = mockMvc.perform(list().param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.totalPages").value(3))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.data.items[*].subscriptionId"));
        }

        assertThat(seen).containsExactlyElementsOf(inserted);
    }

    @Test
    void page와_size가_범위를_벗어나거나_숫자가_아니면_400이다() throws Exception {
        List<String[]> invalid = List.of(
                new String[]{"page", "-1"}, new String[]{"page", "abc"}, new String[]{"page", "1073741824"},
                new String[]{"size", "0"}, new String[]{"size", "101"}, new String[]{"size", "abc"});

        for (String[] param : invalid) {
            mockMvc.perform(list().param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(param[0]));
        }
        mockMvc.perform(list().param("size", "101"))
                .andExpect(jsonPath("$.error.message").value("페이지 요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 크기는 1 이상 100 이하여야 합니다."));
    }

    @Test
    void 허용하지_않은_정렬_필드는_400이다() throws Exception {
        for (String sort : List.of("status,asc", "receivedAt,desc")) {
            mockMvc.perform(list().param("sort", sort))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        mockMvc.perform(get(SUBSCRIPTIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."));
        mockMvc.perform(get(SUBSCRIPTIONS).header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private MockHttpServletRequestBuilder list() {
        return get(SUBSCRIPTIONS).header("X-User-Id", USER_ID.toString());
    }

    private UUID insertSubscription(UUID userId, String status, OffsetDateTime createdAt, OffsetDateTime confirmedAt) {
        return insertSubscription(UUID.randomUUID(), userId, IPO_ID, status, createdAt, confirmedAt);
    }

    // 값은 API 명세 3.4 예시와 같다 (100주, 증거금 1,500,000원, 접수 2026-11-03T10:00:00+09:00)
    private UUID insertSubscription(UUID subscriptionId, UUID userId, UUID ipoId, String status,
                                    OffsetDateTime createdAt, OffsetDateTime confirmedAt) {
        jdbcTemplate.update(
                "INSERT INTO subscription.subscription (subscription_id, request_id, ipo_id, user_id, account_id,"
                        + " requested_quantity, margin_amount, status, attempts, received_at, confirmed_at, version,"
                        + " created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 0, ?, ?, 0, ?, 'SYSTEM:test')",
                subscriptionId, UUID.randomUUID(), ipoId, userId, ACCOUNT_ID, status,
                OffsetDateTime.parse("2026-11-03T10:00:00.123+09:00"), confirmedAt, createdAt);
        return subscriptionId;
    }

    private UUID insertIpo(UUID ipoId, String name) {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, ?, 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', 'OPEN', 0, ?, 'SYSTEM:test')",
                ipoId, name, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
        return ipoId;
    }
}
