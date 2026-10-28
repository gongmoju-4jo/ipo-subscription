package com.gongmoju.account.presentation.account;

import com.gongmoju.account.domain.account.AccountNumberGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 계좌 개설 API. 요청은 Gateway가 넣어 주는 X-User-Id 헤더로 회원을 구분한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:account-open-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountOpenApiTest {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String USER_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 계좌번호 충돌을 만들기 위해 일부 테스트에서만 번호를 정해 준다
    @MockitoSpyBean
    private AccountNumberGenerator accountNumberGenerator;

    private UUID userA;
    private UUID userB;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM account.account");
        jdbcTemplate.update("DELETE FROM account.users");
        userA = insertUser("investor01");
        userB = insertUser("investor02");
    }

    @Test
    void 계좌를_개설하면_201과_잔고_0_계좌번호로_응답한다() throws Exception {
        mockMvc.perform(open(userA, UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(userA.toString()))
                .andExpect(jsonPath("$.data.accountNumber").value(org.hamcrest.Matchers.matchesPattern("[1-9][0-9]{11}")))
                .andExpect(jsonPath("$.data.cashBalance").value(0))
                .andExpect(jsonPath("$.data.marginBalance").value(0))
                .andExpect(jsonPath("$.data.createdAt").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }

    @Test
    void 같은_requestId로_다시_요청하면_계좌를_새로_만들지_않고_같은_계좌를_200으로_응답한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        String first = body(mockMvc.perform(open(userA, requestId)).andExpect(status().isCreated()).andReturn());

        String second = body(mockMvc.perform(open(userA, requestId)).andExpect(status().isOk()).andReturn());

        assertThat(accountIdOf(second)).isEqualTo(accountIdOf(first));
        assertThat(accountNumberOf(second)).isEqualTo(accountNumberOf(first));
        // 메모리 값과 DB에서 읽은 값의 정밀도가 달라도 응답 시각은 같아야 한다 (초 단위로 내려준다)
        assertThat(createdAtOf(second)).isEqualTo(createdAtOf(first)).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+09:00");
        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void 다른_회원이_이미_쓴_requestId면_409로_응답하고_기존_계좌_정보를_주지_않는다() throws Exception {
        UUID requestId = UUID.randomUUID();
        String first = body(mockMvc.perform(open(userA, requestId)).andExpect(status().isCreated()).andReturn());

        String response = body(mockMvc.perform(open(userB, requestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andReturn());

        assertThat(response).doesNotContain(userA.toString()).doesNotContain(accountNumberOf(first));
        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void 같은_requestId가_동시에_들어와도_계좌는_하나만_만들고_모두_성공한다() throws Exception {
        UUID requestId = UUID.randomUUID();
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(open(userA, requestId)).andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : results) {
            statuses.add(result.get());
        }
        executor.shutdown();

        assertThat(statuses).containsOnly(201, 200);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void 같은_회원이_다른_requestId로_요청하면_계좌번호가_다른_계좌가_추가로_만들어진다() throws Exception {
        String first = body(mockMvc.perform(open(userA, UUID.randomUUID())).andExpect(status().isCreated()).andReturn());
        String second = body(mockMvc.perform(open(userA, UUID.randomUUID())).andExpect(status().isCreated()).andReturn());

        assertThat(countAccounts()).isEqualTo(2);
        assertThat(accountNumberOf(first)).isNotEqualTo(accountNumberOf(second));
    }

    @Test
    void 계좌번호가_겹치면_번호만_새로_뽑아_개설한다() throws Exception {
        Mockito.doReturn("100000000001").when(accountNumberGenerator).generate();
        mockMvc.perform(open(userA, UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountNumber").value("100000000001"));

        // 첫 번째로 뽑은 번호가 이미 있어서 겹치고, 두 번째 번호로 개설된다
        Mockito.doReturn("100000000001", "100000000002").when(accountNumberGenerator).generate();
        mockMvc.perform(open(userB, UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountNumber").value("100000000002"));

        assertThat(countAccounts()).isEqualTo(2);
    }

    @Test
    void 계좌번호가_계속_겹쳐_재시도를_넘기면_500이고_계좌는_만들어지지_않는다() throws Exception {
        Mockito.doReturn("100000000001").when(accountNumberGenerator).generate();
        mockMvc.perform(open(userA, UUID.randomUUID())).andExpect(status().isCreated());

        mockMvc.perform(open(userB, UUID.randomUUID()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));

        assertThat(countAccounts()).isEqualTo(1);
    }

    @Test
    void requestId가_없거나_UUID가_아니거나_문자열이_아니면_400으로_requestId를_알려준다() throws Exception {
        for (String json : List.of("{}", "{\"requestId\":null}", "{\"requestId\":\"\"}",
                "{\"requestId\":\"abc\"}", "{\"requestId\":123}")) {
            mockMvc.perform(post(ACCOUNTS).header(USER_HEADER, userA).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("requestId"));
        }
        assertThat(countAccounts()).isZero();
    }

    @Test
    void 본문이_없거나_JSON이_깨졌으면_400이다() throws Exception {
        mockMvc.perform(post(ACCOUNTS).header(USER_HEADER, userA))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(ACCOUNTS).header(USER_HEADER, userA).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 본문의_userId_계좌번호_잔고_같은_알_수_없는_필드는_무시한다() throws Exception {
        String json = "{\"requestId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + userB
                + "\",\"accountNumber\":\"999999999999\",\"cashBalance\":999}";

        mockMvc.perform(post(ACCOUNTS).header(USER_HEADER, userA).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value(userA.toString()))
                .andExpect(jsonPath("$.data.accountNumber").value(org.hamcrest.Matchers.not("999999999999")))
                .andExpect(jsonPath("$.data.cashBalance").value(0));
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        String json = "{\"requestId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post(ACCOUNTS).header(USER_HEADER, "not-a-uuid").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        assertThat(countAccounts()).isZero();
    }

    @Test
    void 토큰은_유효한데_회원이_없으면_500이_아니라_401이다() throws Exception {
        mockMvc.perform(open(UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        assertThat(countAccounts()).isZero();
    }

    private MockHttpServletRequestBuilder open(UUID userId, UUID requestId) {
        return post(ACCOUNTS)
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\"}");
    }

    private UUID insertUser(String loginId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.users (user_id, login_id, password_hash, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, loginId, "hash", "테스트", "USER", OffsetDateTime.now(), "SYSTEM:test");
        return userId;
    }

    private int countAccounts() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account", Integer.class);
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String accountIdOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.data.accountId");
    }

    private static String accountNumberOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.data.accountNumber");
    }

    private static String createdAtOf(String json) {
        return com.jayway.jsonpath.JsonPath.read(json, "$.data.createdAt");
    }
}
