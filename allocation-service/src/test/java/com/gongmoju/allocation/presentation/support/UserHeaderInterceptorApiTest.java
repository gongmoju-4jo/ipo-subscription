package com.gongmoju.allocation.presentation.support;

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
 * 배정 서비스의 사용자 헤더, 관리자 역할 확인 인터셉터(#191)가 경로 값 검사보다 먼저 도는지 확인한다.
 * 서비스 포트로 직접 부르는 경우를 흉내 내므로 Gateway가 넣는 헤더를 요청마다 직접 넣거나 뺀다 (H2 메모리 DB).
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:allocation-user-header-interceptor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@DisplayName("배정 서비스 헤더 확인 인터셉터")
class UserHeaderInterceptorApiTest {

    private static final String USER_ID = "7a1c0000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("배정 결과 조회는 X-User-Id 없이 잘못된 경로 값을 보내면 400이 아니라 401이다")
    void allocationResult_missingHeaderBeforePathValidation() throws Exception {
        mockMvc.perform(get("/api/v1/allocations/subscriptions/not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        // 헤더가 있으면 지금처럼 경로 값 400, 없는 청약이면 404
        mockMvc.perform(get("/api/v1/allocations/subscriptions/not-a-uuid").header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/allocations/subscriptions/" + UUID.randomUUID()).header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("추첨 재현은 ADMIN이 아니면 잘못된 경로 값이어도 400이 아니라 403이다")
    void replay_forbiddenBeforePathValidation() throws Exception {
        mockMvc.perform(get("/api/v1/admin/allocations/ipos/not-a-uuid/replay"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/allocations/ipos/not-a-uuid/replay").header(RequestHeaders.USER_ROLE, "USER"))
                .andExpect(status().isForbidden());
        // ADMIN이면 지금처럼 경로 값 400
        mockMvc.perform(get("/api/v1/admin/allocations/ipos/not-a-uuid/replay").header(RequestHeaders.USER_ROLE, "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("배정 결과 조회는 X-User-Role을 요구하지 않고, 추첨 재현은 X-User-Id를 요구하지 않는다")
    void eachApiChecksOnlyItsHeader() throws Exception {
        mockMvc.perform(get("/api/v1/allocations/subscriptions/" + UUID.randomUUID()).header(RequestHeaders.USER_ID, USER_ID))
                .andExpect(status().isNotFound());
        // 배정 회차가 없는 공모주라 404 (헤더 때문에 401이 아님)
        mockMvc.perform(get("/api/v1/admin/allocations/ipos/" + UUID.randomUUID() + "/replay").header(RequestHeaders.USER_ROLE, "ADMIN"))
                .andExpect(status().isNotFound());
    }
}
