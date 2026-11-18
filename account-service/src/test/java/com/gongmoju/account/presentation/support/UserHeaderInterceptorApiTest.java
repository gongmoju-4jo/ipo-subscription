package com.gongmoju.account.presentation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 API 사용자 헤더 확인 인터셉터(#191)가 걸리는 경로와 걸리지 않는 경로를 확인한다.
 * 서비스 포트로 직접 부르는 경우를 흉내 내므로 Gateway가 넣는 X-User-Id를 요청마다 직접 넣거나 뺀다 (H2 메모리 DB).
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:account-user-header-interceptor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DisplayName("회원 API 사용자 헤더 확인 인터셉터")
class UserHeaderInterceptorApiTest {

    private static final String USER_ID = "7a1c0000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("계좌 개설, 입금은 헤더 없이 잘못된 본문, 경로 값을 보내면 400이 아니라 401이다")
    void missingHeader_beforeBodyAndPathValidation() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
        mockMvc.perform(post("/api/v1/accounts/not-a-uuid/deposits").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        // 헤더 형식 오류도 같음
        mockMvc.perform(post("/api/v1/accounts").header(RequestUser.HEADER, "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // 헤더가 있으면 지금처럼 본문 400
        mockMvc.perform(post("/api/v1/accounts").header(RequestUser.HEADER, USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("조회 API는 헤더 없이 잘못된 쿼리, 경로 값을 보내면 400이 아니라 401이다")
    void missingHeader_beforeQueryAndPathValidation() throws Exception {
        mockMvc.perform(get("/api/v1/accounts").param("page", "-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/not-a-uuid/ledger-entries"))
                .andExpect(status().isUnauthorized());
        // 헤더가 있으면 지금처럼 400
        mockMvc.perform(get("/api/v1/accounts").param("page", "-1").header(RequestUser.HEADER, USER_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("회원가입, 로그인은 헤더 없이 부르는 공개 API라 지금처럼 본문 400이다")
    void publicApis_areNotIntercepted() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 컨트롤러를 찾는 단계에서 끝나 헤더가 없어도 401이 아니라 405다 (인터셉터보다 먼저)")
    void unsupportedMethod_methodNotAllowedFirst() throws Exception {
        mockMvc.perform(put("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("회원 API 아래 없는 경로는 헤더가 없으면 401, 있으면 404다 (Gateway와 같이 인증 먼저)")
    void unknownPath_unauthorizedBeforeNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/x/unknown"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/accounts/x/unknown").header(RequestUser.HEADER, USER_ID))
                .andExpect(status().isNotFound());
    }
}
