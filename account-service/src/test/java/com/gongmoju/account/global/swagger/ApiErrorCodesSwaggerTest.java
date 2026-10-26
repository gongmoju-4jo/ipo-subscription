package com.gongmoju.account.global.swagger;

import com.gongmoju.common.exception.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @ApiErrorCodes를 단 컨트롤러가 실제 /v3/api-docs에 오류 응답 예시로 나오는지 확인 (테스트 전용 컨트롤러)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@Import(ApiErrorCodesSwaggerTest.SampleControllerConfig.class)
class ApiErrorCodesSwaggerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ApiErrorCodes에_적은_에러가_상태별_응답_예시로_나온다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/swagger-sample'].get.responses['400'].content['application/json'].examples.VALIDATION_ERROR").exists())
                .andExpect(jsonPath("$.paths['/swagger-sample'].get.responses['401'].content['application/json'].examples.UNAUTHORIZED").exists())
                .andExpect(jsonPath("$.components.schemas.ApiResponse").exists())
                .andExpect(jsonPath("$.components.securitySchemes.JWT").exists());
    }

    @TestConfiguration
    static class SampleControllerConfig {

        @RestController
        static class SampleController {

            @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED})
            @GetMapping("/swagger-sample")
            public String sample() {
                return "ok";
            }
        }
    }
}
