package com.gongmoju.account.infrastructure.startup;

import com.gongmoju.account.application.user.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("첫 관리자 시드")
class AdminSeedRunnerTest {

    private static final String SEED_LOGIN_ID = "seedadmin01";
    private static final String SEED_PASSWORD = "seedpass1234";

    /**
     * 시드 값을 넣고 띄운 계좌 서비스 (H2 메모리 DB). 기동할 때 실제로 관리자가 만들어지는지 보고,
     * 다시 기동한 경우 등은 같은 빈으로 러너를 새로 만들어 run을 다시 부른다.
     */
    @Nested
    @SpringBootTest(properties = {
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:admin-seed;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=never",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "admin-seed.login-id=" + SEED_LOGIN_ID,
            "admin-seed.password=" + SEED_PASSWORD
    })
    @AutoConfigureMockMvc
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("시드 값이 있을 때")
    class WithSeed {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private UserService userService;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Test
        @DisplayName("기동하면 그 로그인 ID의 ADMIN이 생기고, created_by는 시스템, 로그인하면 role=ADMIN")
        void startup_createsAdmin() throws Exception {
            Map<String, Object> row = rowOf(SEED_LOGIN_ID);
            assertThat(row.get("role")).isEqualTo("ADMIN");
            assertThat(row.get("name")).isEqualTo(UserService.SEED_ADMIN_NAME);
            assertThat(row.get("created_by")).isEqualTo("SYSTEM:account-service");
            assertThat(passwordEncoder.matches(SEED_PASSWORD, (String) row.get("password_hash"))).isTrue();

            String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"" + SEED_LOGIN_ID + "\",\"password\":\"" + SEED_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            String token = JsonPath.read(body, "$.data.accessToken");
            String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            assertThat(JsonPath.<String>read(payload, "$.role")).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("다시 기동하면 그대로 1명이고, ADMIN_PASSWORD를 바꿔도 비밀번호는 바뀌지 않는다")
        void rerun_keepsExistingAdmin(CapturedOutput output) throws Exception {
            String hashBefore = (String) rowOf(SEED_LOGIN_ID).get("password_hash");

            runner(SEED_LOGIN_ID, "changedpass99").run(new DefaultApplicationArguments());

            assertThat(countByLoginId(SEED_LOGIN_ID)).isEqualTo(1);
            assertThat(rowOf(SEED_LOGIN_ID).get("password_hash")).isEqualTo(hashBefore);
            assertThat(output).contains("관리자 계정이 이미 있어 그대로 둡니다").doesNotContain("changedpass99");
        }

        @Test
        @DisplayName("다른 관리자가 있어도 그 로그인 ID가 없으면 만든다")
        void otherAdminExists_stillCreates() throws Exception {
            runner("secondadmin", SEED_PASSWORD).run(new DefaultApplicationArguments());

            assertThat(rowOf("secondadmin").get("role")).isEqualTo("ADMIN");
            assertThat(rowOf(SEED_LOGIN_ID).get("role")).isEqualTo("ADMIN");
        }

        @Test
        @DisplayName("그 로그인 ID가 일반 회원이면 승격하지 않고 WARN만 남긴다")
        void existingUser_notPromoted(CapturedOutput output) throws Exception {
            userService.signUp("investor77", "password1234", "투자자");
            String hashBefore = (String) rowOf("investor77").get("password_hash");

            runner("investor77", SEED_PASSWORD).run(new DefaultApplicationArguments());

            assertThat(rowOf("investor77").get("role")).isEqualTo("USER");
            assertThat(rowOf("investor77").get("password_hash")).isEqualTo(hashBefore);
            assertThat(output).contains("WARN").contains("승격하지 않았습니다").doesNotContain(SEED_PASSWORD);
        }

        @Test
        @DisplayName("시드 비밀번호는 로그에 남지 않는다")
        void password_notLogged(CapturedOutput output) throws Exception {
            runner("thirdadmin", SEED_PASSWORD).run(new DefaultApplicationArguments());

            assertThat(output).contains("첫 관리자 계정을 만들었습니다").doesNotContain(SEED_PASSWORD);
        }

        private AdminSeedRunner runner(String loginId, String password) {
            return new AdminSeedRunner(userService, new AdminSeedProperties(loginId, password));
        }

        private Map<String, Object> rowOf(String loginId) {
            return jdbcTemplate.queryForMap(
                    "SELECT role, name, password_hash, created_by FROM account.users WHERE login_id = ?", loginId);
        }

        private int countByLoginId(String loginId) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.users WHERE login_id = ?", Integer.class, loginId);
            return count == null ? 0 : count;
        }
    }

    /**
     * 시드 값은 원문 그대로 쓴다. @Value로 읽으면 값 안의 #{...}를 SpEL로 계산해 다른 비밀번호가 저장됐다.
     */
    @Nested
    @SpringBootTest(properties = {
            "eureka.client.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:admin-seed-raw;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.mode=never",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "admin-seed.login-id=rawseed01",
            "admin-seed.password=" + RawValue.SPEL_PASSWORD
    })
    @DisplayName("값에 #{...}가 있을 때")
    class RawValue {

        static final String SPEL_PASSWORD = "aa#{12345678}";

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Test
        @DisplayName("SpEL로 계산하지 않고 원문 그대로 비밀번호로 저장한다")
        void password_storedAsWritten() {
            String hash = jdbcTemplate.queryForObject(
                    "SELECT password_hash FROM account.users WHERE login_id = 'rawseed01'", String.class);
            assertThat(passwordEncoder.matches(SPEL_PASSWORD, hash)).isTrue();
            assertThat(passwordEncoder.matches("aa12345678", hash)).isFalse();
        }
    }

    /**
     * 값 검사는 생성자에서 한다 (빈 생성 실패 = 기동 실패). 저장소를 부르지 않으므로 null을 넘긴다.
     */
    @Nested
    @DisplayName("값 검사")
    class Validation {

        @ParameterizedTest(name = "loginId=[{0}], password=[{1}]")
        @CsvSource(value = {"NULL,NULL", "'',''", "'  ','  '"}, nullValues = "NULL")
        @DisplayName("둘 다 비어 있으면 기동하고 아무것도 만들지 않는다")
        void bothBlank_skips(String loginId, String password) {
            assertThatCode(() -> new AdminSeedRunner(null, new AdminSeedProperties(loginId, password)).run(new DefaultApplicationArguments()))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest(name = "loginId=[{0}], password=[{1}] → {2}")
        @CsvSource(value = {
                "seedadmin01,NULL,ADMIN_LOGIN_ID만 있고",
                "NULL,seedpass1234,ADMIN_PASSWORD만 있고",
                "abc,seedpass1234,ADMIN_LOGIN_ID 형식",
                "SeedAdmin01,seedpass1234,ADMIN_LOGIN_ID 형식",
                "seed-admin,seedpass1234,ADMIN_LOGIN_ID 형식",
                "abcdefghij0123456789x,seedpass1234,ADMIN_LOGIN_ID 형식",
                "seedadmin01,short77,ADMIN_PASSWORD 형식",
                "seedadmin01,abcdefghij0123456789x,ADMIN_PASSWORD 형식"
        }, nullValues = "NULL")
        @DisplayName("하나만 있거나 형식이 틀리면 기동 실패, 메시지에 값은 나오지 않는다")
        void invalid_failsStartup(String loginId, String password, String expectedMessage) {
            assertThatThrownBy(() -> new AdminSeedRunner(null, new AdminSeedProperties(loginId, password)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(expectedMessage)
                    .satisfies(e -> {
                        if (loginId != null) {
                            assertThat(e.getMessage()).doesNotContain(loginId);
                        }
                        if (password != null) {
                            assertThat(e.getMessage()).doesNotContain(password);
                        }
                    });
        }

        @Test
        @DisplayName("경계값: 로그인 ID 4자, 20자와 비밀번호 8자, 20자는 통과")
        void boundary_passes() {
            assertThatCode(() -> new AdminSeedRunner(null, new AdminSeedProperties("abcd", "12345678"))).doesNotThrowAnyException();
            assertThatCode(() -> new AdminSeedRunner(null, new AdminSeedProperties("abcdefghij0123456789", "abcdefghij0123456789")))
                    .doesNotThrowAnyException();
        }
    }
}
