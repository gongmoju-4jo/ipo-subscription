package com.gongmoju.account.presentation.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 계좌 상세 API. 계좌는 개설 API가 아니라 DB에 직접 넣어서 잔고를 정확히 정한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:account-detail-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountDetailApiTest {

    private static final String ACCOUNTS = "/api/v1/accounts/";
    private static final String USER_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void 본인_계좌를_상세_조회하면_계좌번호_잔고_소유자를_준다() throws Exception {
        UUID accountId = insertAccount(userA, "483920175364", 1_000_000, 250_000);

        mockMvc.perform(get(ACCOUNTS + accountId).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.data.accountNumber").value("483920175364"))
                .andExpect(jsonPath("$.data.userId").value(userA.toString()))
                .andExpect(jsonPath("$.data.cashBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.marginBalance").value(250_000))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T10:00:00+09:00"));
    }

    @Test
    void 여러_계좌_중_요청한_계좌만_돌려준다() throws Exception {
        insertAccount(userA, "100000000001", 111, 0);
        UUID second = insertAccount(userA, "100000000002", 222, 0);

        mockMvc.perform(get(ACCOUNTS + second).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountNumber").value("100000000002"))
                .andExpect(jsonPath("$.data.cashBalance").value(222));
    }

    @Test
    void 남의_계좌와_없는_계좌는_똑같은_404_응답이다() throws Exception {
        UUID othersAccountId = insertAccount(userA, "100000000001", 1_000_000, 0);

        String forbiddenLooking = mockMvc.perform(get(ACCOUNTS + othersAccountId).header(USER_HEADER, userB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[0].field").value("accountId"))
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get(ACCOUNTS + UUID.randomUUID()).header(USER_HEADER, userB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // 본문이 완전히 같아야 남의 계좌가 있는지 알 수 없다. 남의 계좌 정보도 새어 나가면 안 된다
        assertThat(forbiddenLooking).isEqualTo(missing);
        assertThat(forbiddenLooking).doesNotContain("100000000001").doesNotContain(userA.toString());
    }

    @Test
    void 회원이_아닌_UUID로_조회해도_404다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001", 0, 0);

        mockMvc.perform(get(ACCOUNTS + accountId).header(USER_HEADER, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void accountId가_UUID가_아니면_400으로_accountId를_알려준다() throws Exception {
        mockMvc.perform(get(ACCOUNTS + "abc").header(USER_HEADER, userA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("accountId"));
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001", 0, 0);

        mockMvc.perform(get(ACCOUNTS + accountId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(ACCOUNTS + accountId).header(USER_HEADER, "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    private UUID insertUser(String loginId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.users (user_id, login_id, password_hash, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, loginId, "hash", "테스트", "USER", OffsetDateTime.now(), "SYSTEM:test");
        return userId;
    }

    private UUID insertAccount(UUID userId, String accountNumber, long cashBalance, long marginBalance) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                accountId, userId, UUID.randomUUID(), accountNumber, cashBalance, marginBalance,
                OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "SYSTEM:test");
        return accountId;
    }
}
