package com.gongmoju.subscription.presentation.ipo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공모주 상세 API. 공모주는 DB에 직접 넣는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ipo-detail-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class IpoDetailApiTest {

    private static final String IPOS = "/api/v1/ipos/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.ipo");
    }

    @Test
    void 공모주_정보와_청약_조건을_명세_형식으로_돌려준다() throws Exception {
        UUID ipoId = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
        insertIpo(ipoId, "테스트 공모주 A", "OPEN");

        mockMvc.perform(get(IPOS + ipoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("공모주를 조회했습니다."))
                .andExpect(jsonPath("$.data.ipoId").value(ipoId.toString()))
                .andExpect(jsonPath("$.data.name").value("테스트 공모주 A"))
                .andExpect(jsonPath("$.data.offerPrice").value(15000))
                .andExpect(jsonPath("$.data.retailQuantity").value(100000))
                .andExpect(jsonPath("$.data.equalQuantity").value(50000))
                .andExpect(jsonPath("$.data.minQuantity").value(10))
                .andExpect(jsonPath("$.data.maxQuantity").value(10000))
                .andExpect(jsonPath("$.data.marginRate").value(1.00))
                .andExpect(jsonPath("$.data.subscriptionStartAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.subscriptionEndAt").value("2026-11-04T16:00:00+09:00"))
                .andExpect(jsonPath("$.data.refundDate").value("2026-11-06"))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    void 여러_공모주_중_요청한_공모주만_돌려준다() throws Exception {
        insertIpo(UUID.randomUUID(), "공모주 1", "OPEN");
        UUID second = UUID.randomUUID();
        insertIpo(second, "공모주 2", "CLOSED");

        mockMvc.perform(get(IPOS + second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("공모주 2"))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    void 없는_공모주는_404다() throws Exception {
        mockMvc.perform(get(IPOS + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("공모주를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    @Test
    void ipoId가_UUID가_아니면_400으로_ipoId를_알려준다() throws Exception {
        mockMvc.perform(get(IPOS + "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    // 값은 API 명세 예시(테스트 공모주 A)와 같다
    private void insertIpo(UUID ipoId, String name, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, ?, 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                ipoId, name, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
    }
}
