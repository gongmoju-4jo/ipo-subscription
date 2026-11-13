package com.gongmoju.subscription.presentation.ipo;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 공모주 등록 API (API 명세 3.6, F-18)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * schema.sql의 ipo CHECK 제약까지 걸린 상태에서 저장되는지 본다. 토큰(401)과 관리자 경로(403)는 게이트웨이 테스트(AuthorizationRulesTest)가 맡고,
 * 여기서는 게이트웨이가 넣는 X-User-Role을 직접 넣는다.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@DisplayName("관리자 공모주 등록 API (PostgreSQL)")
class AdminIpoCreateApiTest {

    private static final String ADMIN_IPOS = "/api/v1/admin/ipos";
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 마감 시각이 지금보다 뒤여야 해서 기간은 실행 시각 기준으로 잡는다 (초 단위, KST)
    private OffsetDateTime startAt;
    private OffsetDateTime endAt;
    private String refundDate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM subscription");
        jdbcTemplate.update("DELETE FROM ipo");
        startAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).atOffset(KST);
        endAt = startAt.plusHours(30);
        refundDate = endAt.toLocalDate().plusDays(2).toString();
    }

    @Test
    @DisplayName("관리자가 등록하면 201, 상태 OPEN과 등록 시각을 돌려주고 DB에 저장한다")
    void register_returnsCreatedIpo() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        String body = mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(validBody())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("공모주가 등록되었습니다."))
                .andExpect(jsonPath("$.data.name").value("테스트 공모주 A"))
                .andExpect(jsonPath("$.data.offerPrice").value(15000))
                .andExpect(jsonPath("$.data.retailQuantity").value(100000))
                .andExpect(jsonPath("$.data.equalQuantity").value(50000))
                .andExpect(jsonPath("$.data.minQuantity").value(20))
                .andExpect(jsonPath("$.data.maxQuantity").value(10000))
                .andExpect(jsonPath("$.data.marginRate").value(1.00))
                .andExpect(jsonPath("$.data.subscriptionStartAt").value(startAt.toString()))
                .andExpect(jsonPath("$.data.subscriptionEndAt").value(endAt.toString()))
                .andExpect(jsonPath("$.data.refundDate").value(refundDate))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.createdAt", endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();

        String ipoId = JsonPath.read(body, "$.data.ipoId");
        Instant createdAt = OffsetDateTime.parse(JsonPath.<String>read(body, "$.data.createdAt")).toInstant();
        assertThat(createdAt).isBetween(before, Instant.now());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, status, min_quantity, margin_rate, subscription_end_at FROM ipo WHERE ipo_id = ?::uuid", ipoId);
        assertThat(row.get("name")).isEqualTo("테스트 공모주 A");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("min_quantity")).isEqualTo(20L);
        assertThat((BigDecimal) row.get("margin_rate")).isEqualByComparingTo("1.00");

        // 등록한 공모주는 일반 조회 API로 보인다 (조회 응답에는 createdAt이 없다)
        mockMvc.perform(get("/api/v1/ipos/" + ipoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
    }

    @Test
    @DisplayName("최소 청약 수량과 증거금률을 보내지 않으면 기본값 10, 1.00으로 등록한다")
    void register_appliesDefaults_whenOptionalOmitted() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("minQuantity");
        body.remove("marginRate");

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.minQuantity").value(10))
                .andExpect(jsonPath("$.data.marginRate").value(1.00));
    }

    @Test
    @DisplayName("청약 시작 시각이 이미 지났어도 마감 전이면 등록한다 (시연용으로 바로 청약 중)")
    void register_acceptsStartInPast() throws Exception {
        Map<String, Object> body = validBody();
        body.put("subscriptionStartAt", Instant.now().minus(1, ChronoUnit.HOURS).atOffset(KST).toString());

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("필수값이 빠지면 400이고 빠진 필드가 모두 details에 나온다")
    void register_returns400_whenRequiredMissing() throws Exception {
        Map<String, Object> body = validBody();
        body.remove("name");
        body.remove("offerPrice");
        body.remove("subscriptionEndAt");

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field",
                        containsInAnyOrder("name", "offerPrice", "subscriptionEndAt")));
        assertNoIpoSaved();
    }

    @ParameterizedTest(name = "종목명 \"{0}\"")
    @ValueSource(strings = {"", "   ", "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다"})
    @DisplayName("종목명이 비었거나 30자를 넘으면 400")
    void register_returns400_whenNameInvalid(String name) throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", name);

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[*].field", containsInAnyOrder("name")));
        assertNoIpoSaved();
    }

    @Test
    @DisplayName("종목명 30자는 등록한다")
    void register_acceptsNameOf30Chars() throws Exception {
        Map<String, Object> body = validBody();
        body.put("name", "가".repeat(30));

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("등록 규칙을 어기면 400이고 도메인이 모은 사유가 필드별로 나온다")
    void register_returns400_whenDomainRulesBroken() throws Exception {
        Map<String, Object> body = validBody();
        body.put("equalQuantity", 49999);
        body.put("marginRate", 0.50);
        body.put("subscriptionStartAt", Instant.now().minus(2, ChronoUnit.HOURS).atOffset(KST).toString());
        body.put("subscriptionEndAt", Instant.now().minus(1, ChronoUnit.HOURS).atOffset(KST).toString());

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details", hasSize(3)))
                .andExpect(jsonPath("$.error.details[0].field").value("equalQuantity"))
                .andExpect(jsonPath("$.error.details[1].field").value("marginRate"))
                .andExpect(jsonPath("$.error.details[1].reason").value("증거금률은 1.00(100%)만 등록할 수 있습니다."))
                .andExpect(jsonPath("$.error.details[2].field").value("subscriptionEndAt"))
                .andExpect(jsonPath("$.error.details[2].reason").value("현재 시각보다 뒤여야 합니다."));
        assertNoIpoSaved();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2026-11-03T10:00:00", "2026-11-03", "내일"})
    @DisplayName("시각 형식이 틀리면(시간대 없음 포함) 400")
    void register_returns400_whenDateTimeMalformed(String value) throws Exception {
        Map<String, Object> body = validBody();
        body.put("subscriptionStartAt", value);

        mockMvc.perform(admin(post(ADMIN_IPOS)).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        assertNoIpoSaved();
    }

    @ParameterizedTest(name = "X-User-Role {0}")
    @NullSource
    @ValueSource(strings = {"USER", "admin", ""})
    @DisplayName("관리자가 아니면(헤더 없음, USER, 정의되지 않은 값) 403이고 저장하지 않는다")
    void register_returns403_whenNotAdmin(String role) throws Exception {
        MockHttpServletRequestBuilder request = post(ADMIN_IPOS)
                .contentType(MediaType.APPLICATION_JSON).content(json(validBody()));
        if (role != null) {
            request.header("X-User-Role", role);
        }

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertNoIpoSaved();
    }

    @Test
    @DisplayName("관리자가 아니면 본문이 틀려도 400이 아니라 403이다 (본문 검사보다 역할 확인이 먼저)")
    void register_returns403BeforeValidation_whenNotAdmin() throws Exception {
        mockMvc.perform(post(ADMIN_IPOS).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        mockMvc.perform(post(ADMIN_IPOS).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{잘못된 JSON"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자 경로가 아닌 API는 역할 확인을 받지 않는다")
    void otherApis_areNotAffectedByAdminRoleCheck() throws Exception {
        mockMvc.perform(get("/api/v1/ipos"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Swagger 문서에 성공 응답이 200이 아니라 201로 나오고 응답 형식이 붙는다")
    void apiDocs_showCreatedAsSuccess() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/ipos'].post.responses['201'].content['*/*'].schema").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/ipos'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/admin/ipos'].post.responses['403']").exists());
    }

    // 명세 3.6 요청 예시의 값. 기본값과 다른지 보려고 minQuantity는 20
    private Map<String, Object> validBody() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("name", "테스트 공모주 A");
        body.put("offerPrice", 15000);
        body.put("retailQuantity", 100000);
        body.put("equalQuantity", 50000);
        body.put("minQuantity", 20);
        body.put("maxQuantity", 10000);
        body.put("marginRate", 1.00);
        body.put("subscriptionStartAt", startAt.toString());
        body.put("subscriptionEndAt", endAt.toString());
        body.put("refundDate", refundDate);
        return body;
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Role", "ADMIN").contentType(MediaType.APPLICATION_JSON);
    }

    private String json(Map<String, Object> body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    }

    private void assertNoIpoSaved() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ipo", Long.class)).isZero();
    }
}
