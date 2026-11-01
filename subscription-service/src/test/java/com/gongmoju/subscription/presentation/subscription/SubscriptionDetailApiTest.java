package com.gongmoju.subscription.presentation.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 청약 상세, 상태 조회 API. 청약은 접수 Consumer가 저장하므로 공모주와 청약을 DB에 직접 넣는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:subscription-detail-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SubscriptionDetailApiTest {

    private static final String SUBSCRIPTIONS = "/api/v1/subscriptions/";

    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000099");
    private static final UUID SUBSCRIPTION_ID = UUID.fromString("e8bd1887-fe8e-378c-8f04-13d9d406e984");
    private static final UUID REQUEST_ID = UUID.fromString("3b8f0000-0000-4000-8000-000000000031");
    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("9d4e0000-0000-4000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.subscription");
        jdbcTemplate.update("DELETE FROM subscription.ipo");
        insertIpo();
    }

    @Test
    void 환불까지_끝난_청약은_명세의_15개_필드를_KST_시각으로_돌려준다() throws Exception {
        insertSubscription(USER_ID, "REFUNDED", null, null, 3L, 45000L, 1455000L,
                OffsetDateTime.parse("2026-11-03T10:00:02.456+09:00"));

        mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("청약 상태를 조회했습니다."))
                .andExpect(jsonPath("$.data.subscriptionId").value(SUBSCRIPTION_ID.toString()))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.data.ipoId").value(IPO_ID.toString()))
                .andExpect(jsonPath("$.data.ipoName").value("테스트 공모주 A"))
                .andExpect(jsonPath("$.data.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.data.requestedQuantity").value(100))
                .andExpect(jsonPath("$.data.marginAmount").value(1500000))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.allocatedQuantity").value(3))
                .andExpect(jsonPath("$.data.allocatedAmount").value(45000))
                .andExpect(jsonPath("$.data.refundAmount").value(1455000))
                .andExpect(jsonPath("$.data.receivedAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.confirmedAt").value("2026-11-03T10:00:02+09:00"));
    }

    @Test
    void 접수만_된_청약은_사유_배정_환불_확정시각이_null이다() throws Exception {
        insertSubscription(USER_ID, "PENDING", null, null, null, null, null, null);

        String body = mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"rejectReason\":null", "\"cancelReason\":null", "\"allocatedQuantity\":null",
                "\"allocatedAmount\":null", "\"refundAmount\":null", "\"confirmedAt\":null");
    }

    @Test
    void 배정_0주는_null이_아니라_0으로_돌려준다() throws Exception {
        insertSubscription(USER_ID, "REFUNDED", null, null, 0L, 0L, 1500000L,
                OffsetDateTime.parse("2026-11-03T10:00:02+09:00"));

        mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocatedQuantity").value(0))
                .andExpect(jsonPath("$.data.allocatedAmount").value(0))
                .andExpect(jsonPath("$.data.refundAmount").value(1500000));
    }

    @Test
    void 거절된_청약은_거절_사유를_취소된_청약은_해제_사유를_돌려준다() throws Exception {
        insertSubscription(USER_ID, "REJECTED", "ALREADY_SUBSCRIBED", null, null, null, null, null);
        mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectReason").value("ALREADY_SUBSCRIBED"));

        jdbcTemplate.update("DELETE FROM subscription.subscription");
        insertSubscription(USER_ID, "CANCELLED", null, "TIMEOUT", null, null, null, null);
        mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelReason").value("TIMEOUT"));
    }

    @Test
    void 다른_회원의_청약은_없는_청약과_같은_404다() throws Exception {
        insertSubscription(OTHER_USER_ID, "CONFIRMED", null, null, null, null, null, null);

        String othersBody = mockMvc.perform(detail(SUBSCRIPTION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("청약 내역을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("subscriptionId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("청약 내역을 찾을 수 없습니다."))
                .andReturn().getResponse().getContentAsString();
        String missingBody = mockMvc.perform(detail(UUID.fromString("e8bd0000-0000-3000-8000-000000000000")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(othersBody).isEqualTo(missingBody);
    }

    @Test
    void subscriptionId가_UUID가_아니면_400이다() throws Exception {
        mockMvc.perform(get(SUBSCRIPTIONS + "not-a-uuid").header("X-User-Id", USER_ID.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("subscriptionId"));
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        insertSubscription(USER_ID, "CONFIRMED", null, null, null, null, null, null);

        mockMvc.perform(get(SUBSCRIPTIONS + SUBSCRIPTION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("로그인이 필요합니다."));
        mockMvc.perform(get(SUBSCRIPTIONS + SUBSCRIPTION_ID).header("X-User-Id", "not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private MockHttpServletRequestBuilder detail(UUID subscriptionId) {
        return get(SUBSCRIPTIONS + subscriptionId).header("X-User-Id", USER_ID.toString());
    }

    // 값은 API 명세 3.4 예시와 같다 (100주, 증거금 1,500,000원, 접수 2026-11-03T10:00:00+09:00)
    private void insertSubscription(UUID userId, String status, String rejectReason, String cancelReason,
                                    Long allocatedQuantity, Long allocatedAmount, Long refundAmount,
                                    OffsetDateTime confirmedAt) {
        jdbcTemplate.update(
                "INSERT INTO subscription.subscription (subscription_id, request_id, ipo_id, user_id, account_id,"
                        + " requested_quantity, margin_amount, status, reject_reason, cancel_reason, allocated_quantity,"
                        + " allocated_amount, refund_amount, attempts, received_at, confirmed_at, version, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0, ?, 'SYSTEM:test')",
                SUBSCRIPTION_ID, REQUEST_ID, IPO_ID, userId, ACCOUNT_ID, status, rejectReason, cancelReason,
                allocatedQuantity, allocatedAmount, refundAmount, OffsetDateTime.parse("2026-11-03T10:00:00.123+09:00"),
                confirmedAt, OffsetDateTime.parse("2026-11-03T10:00:01+09:00"));
    }

    private void insertIpo() {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, '테스트 공모주 A', 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', 'ALLOCATED', 0, ?, 'SYSTEM:test')",
                IPO_ID, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
    }
}
