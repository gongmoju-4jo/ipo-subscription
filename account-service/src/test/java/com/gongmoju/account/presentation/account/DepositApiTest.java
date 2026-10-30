package com.gongmoju.account.presentation.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가상 입금 API. 계좌는 개설 API가 아니라 DB에 직접 넣어서 잔고를 정확히 정한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:deposit-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class DepositApiTest {

    private static final String USER_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userA;
    private UUID userB;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM account.ledger_entry");
        jdbcTemplate.update("DELETE FROM account.account");
        jdbcTemplate.update("DELETE FROM account.users");
        userA = insertUser("investor01");
        userB = insertUser("investor02");
        accountId = insertAccount(userA, "100000000001", 0, 250_000);
    }

    @Test
    void 입금하면_201과_함께_예수금이_늘고_청약증거금은_그대로다() throws Exception {
        UUID requestId = UUID.randomUUID();

        mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("입금이 완료되었습니다."))
                .andExpect(jsonPath("$.data.depositId").isNotEmpty())
                .andExpect(jsonPath("$.data.accountId").value(accountId.toString()))
                .andExpect(jsonPath("$.data.amount").value(1_000_000))
                .andExpect(jsonPath("$.data.cashBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.marginBalance").value(250_000))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty());

        assertThat(cashBalance(accountId)).isEqualTo(1_000_000);
        assertThat(marginBalance(accountId)).isEqualTo(250_000);
    }

    @Test
    void 입금하면_DEPOSIT_원장이_한_건_남고_depositId가_원장_ID다() throws Exception {
        UUID requestId = UUID.randomUUID();

        String response = mockMvc.perform(deposit(userA, accountId, body(requestId, 700)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT ledger_entry_id, type, amount, cash_balance_after, margin_balance_after, request_id FROM account.ledger_entry");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("TYPE")).isEqualTo("DEPOSIT");
        assertThat(((Number) rows.get(0).get("AMOUNT")).longValue()).isEqualTo(700);
        assertThat(((Number) rows.get(0).get("CASH_BALANCE_AFTER")).longValue()).isEqualTo(700);
        assertThat(((Number) rows.get(0).get("MARGIN_BALANCE_AFTER")).longValue()).isEqualTo(250_000);
        assertThat(rows.get(0).get("REQUEST_ID").toString()).isEqualTo(requestId.toString());
        assertThat(response).contains(rows.get(0).get("LEDGER_ENTRY_ID").toString());
    }

    @Test
    void 다른_requestId로_입금하면_예수금이_누적된다() throws Exception {
        mockMvc.perform(deposit(userA, accountId, body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isCreated());
        mockMvc.perform(deposit(userA, accountId, body(UUID.randomUUID(), 500)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cashBalance").value(1_500));

        assertThat(cashBalance(accountId)).isEqualTo(1_500);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void 같은_requestId로_다시_요청하면_200으로_처음_응답을_주고_입금은_한_번만_반영된다() throws Exception {
        UUID requestId = UUID.randomUUID();

        String first = mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // 중간에 다른 입금이 있어도 재전송 응답은 처음 입금 직후의 잔고여야 한다
        mockMvc.perform(deposit(userA, accountId, body(UUID.randomUUID(), 300)))
                .andExpect(status().isCreated());
        String replay = mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(cashBalance(accountId)).isEqualTo(1_300);
        assertThat(ledgerCount()).isEqualTo(2);
    }

    @Test
    void 같은_requestId로_다른_금액이면_409고_잔고와_원장은_그대로다() throws Exception {
        UUID requestId = UUID.randomUUID();
        mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000)))
                .andExpect(status().isCreated());

        mockMvc.perform(deposit(userA, accountId, body(requestId, 2_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.error.details[0].field").value("requestId"));

        assertThat(cashBalance(accountId)).isEqualTo(1_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 같은_requestId로_다른_계좌에_보내면_409다() throws Exception {
        UUID otherAccount = insertAccount(userA, "100000000002", 0, 0);
        UUID requestId = UUID.randomUUID();
        mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000)))
                .andExpect(status().isCreated());

        mockMvc.perform(deposit(userA, otherAccount, body(requestId, 1_000)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_RESOURCE"));

        assertThat(cashBalance(otherAccount)).isZero();
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 같은_requestId로_동시에_10건을_보내도_입금은_한_번만_반영된다() throws Exception {
        UUID requestId = UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> mockMvc.perform(deposit(userA, accountId, body(requestId, 1_000)))
                    .andReturn().getResponse().getStatus());
        }
        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        int created = 0;
        int ok = 0;
        for (Future<Integer> future : futures) {
            int statusCode = future.get();
            if (statusCode == 201) {
                created++;
            } else if (statusCode == 200) {
                ok++;
            }
        }
        // 201 1건, 200 9건이고 500이나 409가 없어야 한다
        assertThat(created).isEqualTo(1);
        assertThat(ok).isEqualTo(threads - 1);
        assertThat(cashBalance(accountId)).isEqualTo(1_000);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void 서로_다른_requestId로_동시에_10건을_입금해도_모두_반영되고_금액이_사라지지_않는다() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> mockMvc.perform(deposit(userA, accountId, body(UUID.randomUUID(), 1_000)))
                    .andReturn().getResponse().getStatus());
        }
        List<Future<Integer>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        for (Future<Integer> future : futures) {
            assertThat(future.get()).isEqualTo(201);
        }
        assertThat(cashBalance(accountId)).isEqualTo(threads * 1_000L);
        assertThat(marginBalance(accountId)).isEqualTo(250_000);
        // 거래 후 잔고가 1,000, 2,000, ... 10,000으로 빠짐없이 이어져야 한다 (같은 잔고를 읽고 덮어쓴 건이 없다)
        List<Long> balancesAfter = jdbcTemplate.queryForList(
                "SELECT cash_balance_after FROM account.ledger_entry ORDER BY cash_balance_after", Long.class);
        assertThat(balancesAfter).containsExactly(1_000L, 2_000L, 3_000L, 4_000L, 5_000L,
                6_000L, 7_000L, 8_000L, 9_000L, 10_000L);
    }

    @Test
    void 남의_계좌와_없는_계좌는_똑같은_404_응답이고_잔고는_그대로다() throws Exception {
        String othersAccount = mockMvc.perform(deposit(userB, accountId, body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[0].field").value("accountId"))
                .andReturn().getResponse().getContentAsString();
        String missing = mockMvc.perform(deposit(userB, UUID.randomUUID(), body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // 본문이 완전히 같아야 남의 계좌가 있는지 알 수 없다
        assertThat(othersAccount).isEqualTo(missing);
        assertThat(othersAccount).doesNotContain("100000000001").doesNotContain(userA.toString());
        assertThat(cashBalance(accountId)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void amount가_1_미만이거나_정수가_아니면_400으로_amount를_알려준다() throws Exception {
        UUID requestId = UUID.randomUUID();
        String[] invalidAmounts = {"0", "-1", "1.5", "1.0", "\"100\"", "null", "true"};

        for (String amount : invalidAmounts) {
            mockMvc.perform(deposit(userA, accountId, "{\"requestId\":\"" + requestId + "\",\"amount\":" + amount + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(deposit(userA, accountId, "{\"requestId\":\"" + requestId + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));
        mockMvc.perform(deposit(userA, accountId, "{\"requestId\":\"" + requestId + "\",\"amount\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));

        assertThat(cashBalance(accountId)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void amount가_long_범위를_넘으면_400이다() throws Exception {
        mockMvc.perform(deposit(userA, accountId,
                        "{\"requestId\":\"" + UUID.randomUUID() + "\",\"amount\":99999999999999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 입금_후_예수금이_long_범위를_넘으면_500이_아니라_400이고_잔고와_원장은_그대로다() throws Exception {
        UUID richAccount = insertAccount(userA, "100000000002", 1_000, 0);

        mockMvc.perform(deposit(userA, richAccount, body(UUID.randomUUID(), Long.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("amount"));

        assertThat(cashBalance(richAccount)).isEqualTo(1_000);
        assertThat(ledgerCount()).isZero();
    }

    @Test
    void 입금_후_예수금이_정확히_long_최댓값이면_입금되고_1원_더_넣으면_400이다() throws Exception {
        UUID richAccount = insertAccount(userA, "100000000002", 1_000, 0);
        UUID requestId = UUID.randomUUID();

        mockMvc.perform(deposit(userA, richAccount, body(requestId, Long.MAX_VALUE - 1_000)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cashBalance").value(Long.MAX_VALUE));
        mockMvc.perform(deposit(userA, richAccount, body(UUID.randomUUID(), 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        // 이미 처리한 요청의 재전송은 잔고가 가득 차 있어도 처음 결과를 200으로 돌려준다
        mockMvc.perform(deposit(userA, richAccount, body(requestId, Long.MAX_VALUE - 1_000)))
                .andExpect(status().isOk());

        assertThat(cashBalance(richAccount)).isEqualTo(Long.MAX_VALUE);
        assertThat(ledgerCount()).isEqualTo(1);
    }

    @Test
    void requestId가_없거나_UUID가_아니면_400으로_requestId를_알려준다() throws Exception {
        mockMvc.perform(deposit(userA, accountId, "{\"amount\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("requestId"));
        mockMvc.perform(deposit(userA, accountId, "{\"requestId\":\"abc\",\"amount\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("requestId"));
    }

    @Test
    void 본문이_없거나_JSON이_깨졌으면_400이다() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits").header(USER_HEADER, userA))
                .andExpect(status().isBadRequest());
        mockMvc.perform(deposit(userA, accountId, "{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void accountId가_UUID가_아니면_400으로_accountId를_알려준다() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/abc/deposits")
                        .header(USER_HEADER, userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("accountId"));
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이고_입금되지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header(USER_HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), 1_000)))
                .andExpect(status().isUnauthorized());

        assertThat(cashBalance(accountId)).isZero();
        assertThat(ledgerCount()).isZero();
    }

    private MockHttpServletRequestBuilder deposit(UUID userId, UUID targetAccountId, String content) {
        return post("/api/v1/accounts/" + targetAccountId + "/deposits")
                .header(USER_HEADER, userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(content);
    }

    private String body(UUID requestId, long amount) {
        return "{\"requestId\":\"" + requestId + "\",\"amount\":" + amount + "}";
    }

    private long cashBalance(UUID id) {
        return jdbcTemplate.queryForObject("SELECT cash_balance FROM account.account WHERE account_id = ?", Long.class, id);
    }

    private long marginBalance(UUID id) {
        return jdbcTemplate.queryForObject("SELECT margin_balance FROM account.account WHERE account_id = ?", Long.class, id);
    }

    private int ledgerCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM account.ledger_entry", Integer.class);
    }

    private UUID insertUser(String loginId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.users (user_id, login_id, password_hash, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, loginId, "hash", "테스트", "USER", OffsetDateTime.now(), "SYSTEM:test");
        return userId;
    }

    private UUID insertAccount(UUID userId, String accountNumber, long cashBalance, long marginBalance) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, UUID.randomUUID(), accountNumber, cashBalance, marginBalance,
                OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), "SYSTEM:test");
        return id;
    }
}
