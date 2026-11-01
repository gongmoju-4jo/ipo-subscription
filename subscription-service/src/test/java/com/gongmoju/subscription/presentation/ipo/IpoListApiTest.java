package com.gongmoju.subscription.presentation.ipo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 공모주 목록 API. 공모주는 DB에 직접 넣어서 등록 시각과 값을 정확히 정한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ipo-list-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class IpoListApiTest {

    private static final String IPOS = "/api/v1/ipos";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.ipo");
    }

    @Test
    void 공모주가_없으면_빈_목록을_200으로_응답한다() throws Exception {
        mockMvc.perform(get(IPOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("공모주 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void 항목은_명세의_7개_필드를_KST_시각으로_담고_상세_전용_필드는_담지_않는다() throws Exception {
        UUID ipoId = insertIpo("테스트 공모주 A", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "OPEN");

        mockMvc.perform(get(IPOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].ipoId").value(ipoId.toString()))
                .andExpect(jsonPath("$.data.items[0].name").value("테스트 공모주 A"))
                .andExpect(jsonPath("$.data.items[0].offerPrice").value(15000))
                .andExpect(jsonPath("$.data.items[0].retailQuantity").value(100000))
                .andExpect(jsonPath("$.data.items[0].subscriptionStartAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].subscriptionEndAt").value("2026-11-04T16:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].equalQuantity").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].maxQuantity").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].marginRate").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].refundDate").doesNotExist());
    }

    @Test
    void 청약_전_진행_중_마감된_종목을_상태와_관계없이_모두_돌려준다() throws Exception {
        insertIpo("공모주 1", OffsetDateTime.parse("2026-10-28T09:00:00+09:00"), "OPEN");
        insertIpo("공모주 2", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "CLOSED");
        insertIpo("공모주 3", OffsetDateTime.parse("2026-10-28T11:00:00+09:00"), "ALLOCATED");

        mockMvc.perform(get(IPOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void sort를_보내지_않으면_등록_시각_최신순이다() throws Exception {
        insertIpo("먼저 등록", OffsetDateTime.parse("2026-10-28T09:00:00+09:00"), "OPEN");
        insertIpo("나중 등록", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "OPEN");

        mockMvc.perform(get(IPOS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("나중 등록"))
                .andExpect(jsonPath("$.data.items[1].name").value("먼저 등록"));
    }

    @Test
    void createdAt으로_정렬하면_그_방향을_따른다() throws Exception {
        insertIpo("먼저 등록", OffsetDateTime.parse("2026-10-28T09:00:00+09:00"), "OPEN");
        insertIpo("나중 등록", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "OPEN");

        mockMvc.perform(get(IPOS).param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("먼저 등록"));
        mockMvc.perform(get(IPOS).param("sort", "createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("나중 등록"));
    }

    @Test
    void 등록_시각이_모두_같은_공모주_25개도_20개와_5개로_나뉘고_ipoId_순이며_중복과_누락이_없다() throws Exception {
        // 시드처럼 등록 시각이 모두 같은 경우
        OffsetDateTime sameTime = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");
        List<String> inserted = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            inserted.add(insertIpo(String.format("8b2d0000-0000-4000-8000-%012d", i), "공모주 " + i, sameTime, "OPEN").toString());
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            String body = mockMvc.perform(get(IPOS).param("page", String.valueOf(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(25))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.items.length()").value(page == 0 ? 20 : 5))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.data.items[*].ipoId"));
        }

        assertThat(seen).containsExactlyElementsOf(inserted);
    }

    @Test
    void 마지막_페이지를_넘으면_빈_목록이고_전체_개수는_그대로다() throws Exception {
        insertIpo("공모주", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "OPEN");

        mockMvc.perform(get(IPOS).param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void size를_지정하면_그_크기로_나눈다() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertIpo("공모주 " + i, OffsetDateTime.parse("2026-10-28T10:00:00+09:00").plusMinutes(i), "OPEN");
        }

        mockMvc.perform(get(IPOS).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    @Test
    void page와_size가_범위를_벗어나거나_숫자가_아니면_400이다() throws Exception {
        List<String[]> invalid = List.of(
                new String[]{"page", "-1"}, new String[]{"page", "abc"},
                new String[]{"size", "0"}, new String[]{"size", "-5"},
                new String[]{"size", "101"}, new String[]{"size", "abc"});

        for (String[] param : invalid) {
            mockMvc.perform(get(IPOS).param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(param[0]));
        }
    }

    @Test
    void page와_size_범위_오류는_명세대로_요약_메시지와_필드별_사유를_준다() throws Exception {
        mockMvc.perform(get(IPOS).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("페이지 요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("size"))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 크기는 1 이상 100 이하여야 합니다."));
        mockMvc.perform(get(IPOS).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("페이지 요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("page"))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 번호는 0 이상 107374182 이하여야 합니다."));
    }

    @Test
    void page가_offset_int_범위를_넘으면_400이고_경계값은_허용한다() throws Exception {
        // size 100이면 page 상한은 Integer.MAX_VALUE / 100 = 21474836
        mockMvc.perform(get(IPOS).param("page", "21474837").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("page"))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 번호는 0 이상 21474836 이하여야 합니다."));
        mockMvc.perform(get(IPOS).param("page", "21474836").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void size가_100이면_허용한다() throws Exception {
        mockMvc.perform(get(IPOS).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void 허용하지_않은_정렬_필드는_400이다() throws Exception {
        insertIpo("공모주", OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "OPEN");

        for (String sort : List.of("offerPrice,asc", "createdAt,desc&sort=name,asc")) {
            mockMvc.perform(get(IPOS + "?sort=" + sort))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        }
    }

    private UUID insertIpo(String name, OffsetDateTime createdAt, String status) {
        return insertIpo(UUID.randomUUID().toString(), name, createdAt, status);
    }

    // 값은 API 명세 예시(테스트 공모주 A)와 같다
    private UUID insertIpo(String ipoId, String name, OffsetDateTime createdAt, String status) {
        UUID id = UUID.fromString(ipoId);
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, ?, 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                id, name, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, createdAt);
        return id;
    }
}
