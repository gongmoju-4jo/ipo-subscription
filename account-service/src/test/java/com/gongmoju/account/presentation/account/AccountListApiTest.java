package com.gongmoju.account.presentation.account;

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

// 내 계좌 목록 API. 계좌는 개설 API가 아니라 DB에 직접 넣어서 개설 시각과 잔고를 정확히 정한다 (H2 메모리 DB)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:account-list-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AccountListApiTest {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String USER_HEADER = "X-User-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID userA;
    private UUID userB;
    private int numberSeq;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM account.account");
        jdbcTemplate.update("DELETE FROM account.users");
        userA = insertUser("investor01");
        userB = insertUser("investor02");
        numberSeq = 0;
    }

    @Test
    void 계좌가_없으면_빈_목록을_200으로_응답한다() throws Exception {
        mockMvc.perform(get(ACCOUNTS).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void 본인_계좌만_나오고_다른_회원의_계좌는_나오지_않는다() throws Exception {
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), 1_000_000, 0);
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T11:00:00+09:00"), 0, 0);
        String othersNumber = insertAccount(userB, OffsetDateTime.parse("2026-10-28T12:00:00+09:00"), 0, 0);

        String body = mockMvc.perform(get(ACCOUNTS).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(othersNumber).doesNotContain(userB.toString());
    }

    @Test
    void 개설_시각이_최신인_계좌가_먼저_나온다() throws Exception {
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T09:00:00+09:00"), 100, 0);
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T11:00:00+09:00"), 300, 0);
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), 200, 0);

        mockMvc.perform(get(ACCOUNTS).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cashBalance").value(300))
                .andExpect(jsonPath("$.data.items[1].cashBalance").value(200))
                .andExpect(jsonPath("$.data.items[2].cashBalance").value(100));
    }

    @Test
    void 항목은_계좌번호_잔고_개설시각을_담고_userId는_담지_않는다() throws Exception {
        String number = insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), 1_000_000, 250_000);

        mockMvc.perform(get(ACCOUNTS).header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].accountId").exists())
                .andExpect(jsonPath("$.data.items[0].accountNumber").value(number))
                .andExpect(jsonPath("$.data.items[0].cashBalance").value(1_000_000))
                .andExpect(jsonPath("$.data.items[0].marginBalance").value(250_000))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-28T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.items[0].userId").doesNotExist());
    }

    @Test
    void 개설_시각이_모두_같은_계좌_25개도_20개와_5개로_나뉘고_중복과_누락이_없다() throws Exception {
        OffsetDateTime sameTime = OffsetDateTime.parse("2026-10-28T10:00:00+09:00");
        for (int i = 0; i < 25; i++) {
            insertAccount(userA, sameTime, i, 0);
        }

        List<String> ids = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            String body = mockMvc.perform(get(ACCOUNTS).param("page", String.valueOf(page)).header(USER_HEADER, userA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(25))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.items.length()").value(page == 0 ? 20 : 5))
                    .andReturn().getResponse().getContentAsString();
            ids.addAll(JsonPath.read(body, "$.data.items[*].accountId"));
        }

        assertThat(ids).hasSize(25).doesNotHaveDuplicates();
    }

    @Test
    void 마지막_페이지를_넘으면_빈_목록이고_전체_개수는_그대로다() throws Exception {
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), 0, 0);

        mockMvc.perform(get(ACCOUNTS).param("page", "5").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void size를_지정하면_그_크기로_나눈다() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00").plusMinutes(i), 0, 0);
        }

        mockMvc.perform(get(ACCOUNTS).param("size", "2").header(USER_HEADER, userA))
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
            mockMvc.perform(get(ACCOUNTS).param(param[0], param[1]).header(USER_HEADER, userA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(param[0]));
        }
    }

    @Test
    void size가_100이면_허용한다() throws Exception {
        mockMvc.perform(get(ACCOUNTS).param("size", "100").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void sort_파라미터는_받지_않고_개설_시각_최신순으로_고정된다() throws Exception {
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T09:00:00+09:00"), 100, 0);
        insertAccount(userA, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"), 200, 0);

        mockMvc.perform(get(ACCOUNTS).param("sort", "cashBalance,asc").header(USER_HEADER, userA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].cashBalance").value(200));
    }

    @Test
    void X_User_Id_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        mockMvc.perform(get(ACCOUNTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get(ACCOUNTS).header(USER_HEADER, "not-a-uuid"))
                .andExpect(status().isUnauthorized());
    }

    private UUID insertUser(String loginId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO account.users (user_id, login_id, password_hash, name, role, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, loginId, "hash", "테스트", "USER", OffsetDateTime.now(), "SYSTEM:test");
        return userId;
    }

    // 계좌번호는 테스트마다 겹치지 않는 값을 쓴다. 넣은 번호를 돌려준다
    private String insertAccount(UUID userId, OffsetDateTime createdAt, long cashBalance, long marginBalance) {
        String number = String.format("%012d", 100_000_000_000L + (++numberSeq));
        jdbcTemplate.update(
                "INSERT INTO account.account (account_id, user_id, request_id, account_number, cash_balance, margin_balance, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, UUID.randomUUID(), number, cashBalance, marginBalance, createdAt, "SYSTEM:test");
        return number;
    }
}
