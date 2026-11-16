package com.gongmoju.account.presentation.user;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 계정 생성 API (POST /api/v1/admin/users). H2 메모리 DB.
 * 토큰(401)과 관리자 경로(403)는 게이트웨이 테스트가 맡고, 여기서는 게이트웨이가 넣는 X-User-Id, X-User-Role을 직접 넣는다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:admin-user-create-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DisplayName("관리자 계정 생성 API")
class AdminUserCreateApiTest {

    private static final String ADMIN_USERS = "/api/v1/admin/users";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "password1234";
    // 요청한 관리자. Audit의 created_by로 남는지 본다
    private static final String REQUESTER_ID = "7a1c0000-0000-4000-8000-0000000000aa";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM account.users");
    }

    @Test
    @DisplayName("관리자가 만들면 201, 회원가입과 같은 항목으로 응답하고 비밀번호는 응답에 없다")
    void create_returnsCreatedUser() throws Exception {
        MvcResult result = mockMvc.perform(asAdmin(json("operator01", PASSWORD, "운영자")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("관리자 계정을 만들었습니다."))
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.loginId").value("operator01"))
                .andExpect(jsonPath("$.data.name").value("운영자"))
                .andExpect(jsonPath("$.data.createdAt").value(endsWith("+09:00")))
                .andExpect(jsonPath("$.data.role").doesNotExist())
                .andReturn();

        assertThat(body(result)).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("역할은 ADMIN, 비밀번호는 BCrypt 해시로 저장하고, 만든 관리자가 created_by에 남는다")
    void create_storesAdminWithAudit() throws Exception {
        mockMvc.perform(asAdmin(json("operator01", PASSWORD, "운영자"))).andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT role, password_hash, created_by FROM account.users WHERE login_id = 'operator01'");
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat((String) row.get("password_hash")).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(row.get("created_by")).isEqualTo(REQUESTER_ID);
    }

    @Test
    @DisplayName("요청에 role을 USER로 넣어도 무시하고 ADMIN으로 만든다")
    void create_ignoresRoleInBody() throws Exception {
        String body = "{\"loginId\":\"operator01\",\"password\":\"" + PASSWORD + "\",\"name\":\"운영자\",\"role\":\"USER\"}";

        mockMvc.perform(asAdmin(body)).andExpect(status().isCreated());

        assertThat(roleOf("operator01")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("만든 계정으로 로그인하면 토큰의 role이 ADMIN이다")
    void createdAdmin_canLoginAsAdmin() throws Exception {
        mockMvc.perform(asAdmin(json("operator01", PASSWORD, "운영자"))).andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"operator01\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String token = JsonPath.read(body(login), "$.data.accessToken");
        Jwt jwt = decoder().decode(token);
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("이미 쓰는 loginId면 409로 loginId를 알려주고, 기존 회원의 역할은 바뀌지 않는다")
    void create_returns409_whenLoginIdTaken() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("investor01", PASSWORD, "홍길동")))
                .andExpect(status().isCreated());

        mockMvc.perform(asAdmin(json("investor01", "otherpass1234", "운영자")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[0].field").value("loginId"));

        assertThat(roleOf("investor01")).isEqualTo("USER");
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("형식이 틀리면 400으로 틀린 필드를 알려주고 만들지 않는다 (회원가입과 같은 규칙)")
    void create_returns400_whenInvalid() throws Exception {
        List<String> bodies = List.of(
                json("Operator01", PASSWORD, "운영자"),
                json("abc", PASSWORD, "운영자"),
                json("operator01", "1234567", "운영자"),
                json("operator01", "a".repeat(21), "운영자"),
                json("operator01", PASSWORD, "가".repeat(21)),
                json("operator01", PASSWORD, " "));
        List<String> fields = List.of("loginId", "loginId", "password", "password", "name", "name");

        for (int i = 0; i < bodies.size(); i++) {
            mockMvc.perform(asAdmin(bodies.get(i)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(fields.get(i)));
        }
        assertThat(countUsers()).isZero();
    }

    @ParameterizedTest(name = "X-User-Role {0}")
    @NullSource
    @ValueSource(strings = {"USER", "admin", ""})
    @DisplayName("관리자가 아니면(헤더 없음, USER, 정의되지 않은 값) 403이고 만들지 않는다")
    void create_returns403_whenNotAdmin(String role) throws Exception {
        MockHttpServletRequestBuilder request = post(ADMIN_USERS).header("X-User-Id", REQUESTER_ID)
                .contentType(MediaType.APPLICATION_JSON).content(json("operator01", PASSWORD, "운영자"));
        if (role != null) {
            request.header("X-User-Role", role);
        }

        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        assertThat(countUsers()).isZero();
    }

    @Test
    @DisplayName("관리자가 아니면 본문이 틀려도 400이 아니라 403이다 (본문 검사보다 역할 확인이 먼저)")
    void create_returns403BeforeValidation_whenNotAdmin() throws Exception {
        mockMvc.perform(post(ADMIN_USERS).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.details").doesNotExist());

        mockMvc.perform(post(ADMIN_USERS).header("X-User-Role", "USER")
                        .contentType(MediaType.APPLICATION_JSON).content("{잘못된 JSON"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("관리자 경로가 아닌 회원가입은 역할 확인을 받지 않는다")
    void signUp_isNotAffectedByAdminRoleCheck() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content(json("investor01", PASSWORD, "홍길동")))
                .andExpect(status().isCreated());

        assertThat(roleOf("investor01")).isEqualTo("USER");
    }

    @Test
    @DisplayName("Swagger에 성공 응답이 201로, 403 응답 예시가 표시된다")
    void swagger_showsCreatedAndForbidden() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/admin/users'].post.responses['409']").exists());
    }

    private MockHttpServletRequestBuilder asAdmin(String body) {
        return post(ADMIN_USERS)
                .header("X-User-Id", REQUESTER_ID)
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String json(String loginId, String password, String name) {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\"}";
    }

    private String roleOf(String loginId) {
        return jdbcTemplate.queryForObject("SELECT role FROM account.users WHERE login_id = ?", String.class, loginId);
    }

    private int countUsers() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.users", Integer.class);
        return count == null ? 0 : count;
    }

    private NimbusJwtDecoder decoder() {
        SecretKeySpec key = new SecretKeySpec(Base64.getDecoder().decode(jwtSecret.trim()), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
