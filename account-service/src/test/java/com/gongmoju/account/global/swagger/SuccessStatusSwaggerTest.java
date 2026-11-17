package com.gongmoju.account.global.swagger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ResponseEntity로 상태를 정하는 API는 Swagger가 성공 상태를 알 수 없어 200으로 표시하므로, 컨트롤러에 적은 성공 응답이
// 실제 /v3/api-docs에 나오는지 확인한다 (#191). 응답 형식(content)이 붙는지와 오류 응답이 그대로인지도 본다
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:success-status-swagger;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@DisplayName("Swagger 성공 응답 코드")
class SuccessStatusSwaggerTest {

    private static final String SIGN_UP = "$.paths['/api/v1/users'].post.responses";
    private static final String OPEN = "$.paths['/api/v1/accounts'].post.responses";
    private static final String DEPOSIT = "$.paths['/api/v1/accounts/{accountId}/deposits'].post.responses";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("회원가입은 성공 응답이 200이 아니라 201로 나온다")
    void signUp_showsCreated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(SIGN_UP + "['201'].content['*/*'].schema").exists())
                .andExpect(jsonPath(SIGN_UP + "['200']").doesNotExist())
                .andExpect(jsonPath(SIGN_UP + "['400']").exists());
    }

    @Test
    @DisplayName("계좌 개설과 입금은 처음 요청 201과 같은 requestId 재요청 200이 함께 나온다")
    void openAndDeposit_showCreatedAndOk() throws Exception {
        for (String responses : new String[]{OPEN, DEPOSIT}) {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(responses + "['201'].content['*/*'].schema").exists())
                    .andExpect(jsonPath(responses + "['200'].content['*/*'].schema").exists())
                    .andExpect(jsonPath(responses + "['200'].description").value(org.hamcrest.Matchers.containsString("같은 requestId")))
                    .andExpect(jsonPath(responses + "['401']").exists());
        }
    }
}
