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

// 계좌 거래 내역(원장) API. 원장은 DB에 직접 넣어서 순서와 금액을 정확히 정한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:account-ledger-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountLedgerApiTest {

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
        jdbcTemplate.update("DELETE FROM account.ledger_entry");
        jdbcTemplate.update("DELETE FROM account.account");
        jdbcTemplate.update("DELETE FROM account.users");
        userA = insertUser("investor01");
        userB = insertUser("investor02");
    }

    @Test
    void 본인_계좌의_원장을_최신순으로_거래_후_잔고와_함께_준다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001");
        UUID subscriptionId = UUID.randomUUID();
        insertLedger(accountId, "DEPOSIT", 1_000_000, 1_000_000, 0, null, "2026-11-06T00:10:00+09:00");
        insertLedger(accountId, "MARGIN_PENDING", 150_000, 850_000, 150_000, subscriptionId, "2026-11-06T00:11:00+09:00");

        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[0].type").value("MARGIN_PENDING"))
                .andExpect(jsonPath("$.data.items[0].amount").value(150_000))
                .andExpect(jsonPath("$.data.items[0].cashBalanceAfter").value(850_000))
                .andExpect(jsonPath("$.data.items[0].marginBalanceAfter").value(150_000))
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-06T00:11:00+09:00"))
                .andExpect(jsonPath("$.data.items[1].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.data.items[1].subscriptionId").doesNotExist());
    }

    @Test
    void 다른_계좌의_원장은_섞이지_않는다() throws Exception {
        UUID mine = insertAccount(userA, "100000000001");
        UUID mySecond = insertAccount(userA, "100000000002");
        insertLedger(mine, "DEPOSIT", 100, 100, 0, null, "2026-11-06T00:10:00+09:00");
        insertLedger(mySecond, "DEPOSIT", 222, 222, 0, null, "2026-11-06T00:12:00+09:00");

        mockMvc.perform(get(ACCOUNTS + mine + "/ledger-entries").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].amount").value(100));
    }

    @Test
    void 남의_계좌와_없는_계좌는_똑같은_404_응답이다() throws Exception {
        UUID others = insertAccount(userA, "100000000001");
        insertLedger(others, "DEPOSIT", 1_000_000, 1_000_000, 0, null, "2026-11-06T00:10:00+09:00");

        String forbiddenLooking = mockMvc.perform(get(ACCOUNTS + others + "/ledger-entries").header(USER_HEADER, userB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(get(ACCOUNTS + UUID.randomUUID() + "/ledger-entries").header(USER_HEADER, userB))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(forbiddenLooking).isEqualTo(missing);
        assertThat(forbiddenLooking).doesNotContain("1000000");
    }

    @Test
    void 정렬할_수_없는_필드와_범위_밖_페이지는_400이다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001");

        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries").param("sort", "amount,desc").header(USER_HEADER, userA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("sort"));
        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries").param("size", "0").header(USER_HEADER, userA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("size"));
    }

    @Test
    void type을_주면_그_거래_종류만_준다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001");
        insertLedger(accountId, "DEPOSIT", 1_000_000, 1_000_000, 0, null, "2026-11-06T00:10:00+09:00");
        insertLedger(accountId, "MARGIN_PENDING", 150_000, 850_000, 150_000, UUID.randomUUID(), "2026-11-06T00:11:00+09:00");

        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries").param("type", "DEPOSIT").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("거래내역을 조회했습니다."))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].type").value("DEPOSIT"));
    }

    @Test
    void 없는_type_값은_400이다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001");

        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries").param("type", "WITHDRAW").header(USER_HEADER, userA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void X_User_Id_헤더가_없으면_401이다() throws Exception {
        UUID accountId = insertAccount(userA, "100000000001");

        mockMvc.perform(get(ACCOUNTS + accountId + "/ledger-entries"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private UUID insertUser(String loginId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.users (user_id, login_id, password_hash, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, loginId, "hash", "테스트", "USER", OffsetDateTime.now(), "SYSTEM:test");
        return userId;
    }

    private UUID insertAccount(UUID userId, String accountNumber) {
        UUID accountId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                accountId, userId, UUID.randomUUID(), accountNumber, 0, 0,
                OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "SYSTEM:test");
        return accountId;
    }

    private void insertLedger(UUID accountId, String type, long amount, long cashAfter, long marginAfter, UUID subscriptionId, String at) {
        jdbcTemplate.update(
                "INSERT INTO account.ledger_entry (ledger_entry_id, account_id, type, amount, cash_balance_after, margin_balance_after,"
                        + " subscription_id, request_id, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), accountId, type, amount, cashAfter, marginAfter, subscriptionId,
                "DEPOSIT".equals(type) ? UUID.randomUUID() : null, OffsetDateTime.parse(at), "SYSTEM:test");
    }
}
