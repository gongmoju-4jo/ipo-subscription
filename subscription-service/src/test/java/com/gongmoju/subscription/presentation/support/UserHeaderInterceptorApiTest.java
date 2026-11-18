package com.gongmoju.subscription.presentation.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 API 사용자 헤더 확인 인터셉터(#191)가 걸리는 경로와 걸리지 않는 경로를 확인한다. 청약 신청의 본문보다 헤더가 먼저인 것은 SubscriptionCreateApiTest.
 * 서비스 포트로 직접 부르는 경우를 흉내 내므로 Gateway가 넣는 헤더를 요청마다 직접 넣거나 뺀다 (H2 메모리 DB).
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:user-header-interceptor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
    @DisplayName("헤더 없이 잘못된 쿼리, 경로 값을 보내면 400이 아니라 401이다")
    void missingHeader_beforeQueryAndPathValidation() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions").param("page", "-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/subscriptions/not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        // 헤더가 있으면 지금처럼 쿼리, 경로 값 400
        mockMvc.perform(get("/api/v1/subscriptions").param("page", "-1").header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/subscriptions/not-a-uuid").header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("청약 조회(목록, 상세)는 X-Request-Received-At 없이 X-User-Id만 있어도 된다")
    void queries_doNotRequireReceivedAt() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions").header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isOk());
        // 없는 청약이라 404 (헤더 때문에 401이 아님)
        mockMvc.perform(get("/api/v1/subscriptions/" + UUID.randomUUID()).header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("공모주 조회와 내부 API는 사용자 헤더 없이 지금처럼 동작한다")
    void unprotectedPaths_workWithoutHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/ipos"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/internal/ipos").param("status", "OPEN"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("회원 API 아래 없는 경로는 헤더가 없으면 401, 있으면 404다 (Gateway와 같이 인증 먼저)")
    void unknownPath_unauthorizedBeforeNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions/x/unknown"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/subscriptions/x/unknown").header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isNotFound());
    }
}
