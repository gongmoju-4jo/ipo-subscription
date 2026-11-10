package com.gongmoju.subscription.presentation.internal;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 청약 중 공모주 목록 내부 API (API 명세 4.4). 게이트웨이가 사용자 헤더 없이 주기적으로 부르므로 요청에 X-User-Id를 넣지 않는다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:internal-open-ipo-list-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class InternalOpenIpoListApiTest {

    private static final UUID OPEN_A = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID OPEN_B = UUID.fromString("8b2d0000-0000-4000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.subscription");
        jdbcTemplate.update("DELETE FROM subscription.ipo");
    }

    @Test
    void 상태가_OPEN인_공모주만_돌려준다() throws Exception {
        insertIpo(OPEN_A, "OPEN");
        insertIpo(OPEN_B, "OPEN");
        insertIpo(UUID.randomUUID(), "CLOSED");
        insertIpo(UUID.randomUUID(), "ALLOCATED");

        String body = mockMvc.perform(get("/internal/ipos").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("청약 중인 공모주 목록을 조회했습니다."))
                .andReturn().getResponse().getContentAsString();

        List<String> ids = JsonPath.read(body, "$.data[*].ipoId");
        assertThat(ids).containsExactlyInAnyOrder(OPEN_A.toString(), OPEN_B.toString());
    }

    @Test
    void 항목은_공모주_ID와_청약_시작_마감_시각_3개를_KST로_담는다() throws Exception {
        insertIpo(OPEN_A, "OPEN");

        String body = mockMvc.perform(get("/internal/ipos").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ipoId").value(OPEN_A.toString()))
                .andExpect(jsonPath("$.data[0].subscriptionStartAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].subscriptionEndAt").value("2026-11-04T16:00:00+09:00"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item = JsonPath.read(body, "$.data[0]");
        assertThat(item).containsOnlyKeys("ipoId", "subscriptionStartAt", "subscriptionEndAt");
    }

    @Test
    void 청약_중인_공모주가_없으면_빈_배열이다() throws Exception {
        insertIpo(UUID.randomUUID(), "CLOSED");

        mockMvc.perform(get("/internal/ipos").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void status가_OPEN이_아니면_400() throws Exception {
        for (String status : List.of("CLOSED", "ALLOCATED", "open", "")) {
            mockMvc.perform(get("/internal/ipos").param("status", status))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("status"));
        }
    }

    @Test
    void status가_없으면_400() throws Exception {
        mockMvc.perform(get("/internal/ipos"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));
    }

    private void insertIpo(UUID ipoId, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, '테스트 공모주', 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                ipoId, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
    }
}
