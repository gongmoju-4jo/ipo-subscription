package com.gongmoju.account.presentation.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 회원가입(POST /api/v1/users)과 로그인(POST /api/v1/auth/login). 두 API 모두 인증 없이 호출한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:signup-login-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SignUpLoginApiTest {

    private static final String USERS = "/api/v1/users";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "password1234";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 발급한 토큰을 Gateway와 같은 방식(같은 키, HS256)으로 풀어 보기 위해 테스트용 키를 읽는다
    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM account.users");
    }

    // ---- 회원가입 ----

    @Test
    void 가입하면_201과_회원_정보로_응답하고_비밀번호는_응답에_없다() throws Exception {
        MvcResult result = mockMvc.perform(signUp("investor01", PASSWORD, "홍길동"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.loginId").value("investor01"))
                .andExpect(jsonPath("$.data.name").value("홍길동"))
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andReturn();

        assertThat(body(result)).doesNotContain(PASSWORD).doesNotContainIgnoringCase("password");
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void 비밀번호는_BCrypt_해시로_저장하고_역할은_USER다() throws Exception {
        mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andExpect(status().isCreated());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT password_hash, role FROM account.users WHERE login_id = 'investor01'");

        assertThat((String) row.get("password_hash")).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(row.get("role")).isEqualTo("USER");
    }

    @Test
    void 요청에_role을_넣어도_무시하고_USER로_가입한다() throws Exception {
        String json = "{\"loginId\":\"investor01\",\"password\":\"" + PASSWORD + "\",\"name\":\"홍길동\",\"role\":\"ADMIN\"}";

        mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT role FROM account.users WHERE login_id = 'investor01'", String.class)).isEqualTo("USER");
    }

    @Test
    void 이미_쓰는_loginId로_가입하면_409로_loginId를_알려주고_회원은_늘지_않는다() throws Exception {
        mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andExpect(status().isCreated());

        mockMvc.perform(signUp("investor01", "otherpass1234", "김철수"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[0].field").value("loginId"));

        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void 같은_loginId가_동시에_가입해도_한_건만_성공하고_나머지는_409다() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        executor.shutdown();

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(countUsers()).isEqualTo(1);
    }

    @Test
    void 형식이_틀린_값은_400으로_틀린_필드를_모두_알려주고_가입되지_않는다() throws Exception {
        // loginId: 대문자, 3자, 21자, 특수문자 / password: 7자, 21자 / name: 21자, 공백
        String longName = "가".repeat(21);
        List<String> bodies = List.of(
                json("Investor01", PASSWORD, "홍길동"),
                json("abc", PASSWORD, "홍길동"),
                json("a".repeat(21), PASSWORD, "홍길동"),
                json("investor-01", PASSWORD, "홍길동"),
                json("investor01", "1234567", "홍길동"),
                json("investor01", "a".repeat(21), "홍길동"),
                json("investor01", PASSWORD, longName),
                json("investor01", PASSWORD, " "));
        List<String> fields = List.of("loginId", "loginId", "loginId", "loginId", "password", "password", "name", "name");

        for (int i = 0; i < bodies.size(); i++) {
            mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content(bodies.get(i)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(fields.get(i)));
        }
        assertThat(countUsers()).isZero();
    }

    @Test
    void 필수값이_모두_없으면_400이고_세_필드가_모두_details에_담긴다() throws Exception {
        MvcResult result = mockMvc.perform(post(USERS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andReturn();

        List<String> fields = JsonPath.read(body(result), "$.error.details[*].field");
        assertThat(fields).containsExactlyInAnyOrder("loginId", "password", "name");
        assertThat(countUsers()).isZero();
    }

    @Test
    void 비밀번호_20자는_가입할_수_있다() throws Exception {
        // 8~20자 경계. 한글 20자는 60바이트라 BCrypt 72바이트 한도 안이다
        mockMvc.perform(signUp("investor01", "가".repeat(20), "홍길동")).andExpect(status().isCreated());
        mockMvc.perform(signUp("investor02", "a".repeat(8), "홍길동")).andExpect(status().isCreated());

        assertThat(countUsers()).isEqualTo(2);
    }

    // ---- 로그인 ----

    @Test
    void 로그인하면_200과_토큰을_주고_토큰의_sub_role_iss가_가입한_회원과_맞다() throws Exception {
        String userId = JsonPath.read(
                body(mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andReturn()), "$.data.userId");

        MvcResult result = mockMvc.perform(login("investor01", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").isNumber())
                .andReturn();

        String accessToken = JsonPath.read(body(result), "$.data.accessToken");
        long expiresIn = ((Number) JsonPath.read(body(result), "$.data.expiresIn")).longValue();
        Jwt jwt = decoder().decode(accessToken);
        assertThat(jwt.getSubject()).isEqualTo(userId);
        assertThat(jwt.<String>getClaim("role")).isEqualTo("USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("account-service");
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(expiresIn);
    }

    @Test
    void 로그인_ID가_없는_경우와_비밀번호가_틀린_경우는_같은_401_응답이다() throws Exception {
        mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andExpect(status().isCreated());

        String unknownId = body(mockMvc.perform(login("nobody01", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn());
        String wrongPassword = body(mockMvc.perform(login("investor01", "wrongpass1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andReturn());

        assertThat(unknownId).isEqualTo(wrongPassword);
        assertThat(unknownId).doesNotContain("accessToken");
    }

    @Test
    void 가입_규칙에_맞지_않는_값으로_로그인해도_400이_아니라_같은_401이다() throws Exception {
        mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andExpect(status().isCreated());

        // 대문자 ID, 너무 짧은 비밀번호, BCrypt 한도(72바이트)를 넘는 비밀번호
        for (MockHttpServletRequestBuilder request : List.of(
                login("INVESTOR01", PASSWORD), login("investor01", "short"), login("investor01", "가".repeat(100)))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test
    void 로그인_값이_비어_있거나_없으면_400이다() throws Exception {
        for (String json : List.of("{}", "{\"loginId\":\"investor01\"}", "{\"password\":\"" + PASSWORD + "\"}",
                "{\"loginId\":\"\",\"password\":\"\"}")) {
            mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void 로그인_응답에_비밀번호나_해시는_없다() throws Exception {
        mockMvc.perform(signUp("investor01", PASSWORD, "홍길동")).andExpect(status().isCreated());

        String response = body(mockMvc.perform(login("investor01", PASSWORD)).andReturn());

        assertThat(response).doesNotContain(PASSWORD).doesNotContain("$2");
    }

    private MockHttpServletRequestBuilder signUp(String loginId, String password, String name) {
        return post(USERS).contentType(MediaType.APPLICATION_JSON).content(json(loginId, password, name));
    }

    private MockHttpServletRequestBuilder login(String loginId, String password) {
        return post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}");
    }

    private static String json(String loginId, String password, String name) {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\",\"name\":\"" + name + "\"}";
    }

    private NimbusJwtDecoder decoder() {
        byte[] key = Base64.getDecoder().decode(jwtSecret);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private int countUsers() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.users", Integer.class);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }
}
