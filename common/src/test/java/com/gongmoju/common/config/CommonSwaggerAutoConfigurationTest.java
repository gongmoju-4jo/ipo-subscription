package com.gongmoju.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CommonSwaggerAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommonSwaggerAutoConfiguration.class)
            .withPropertyValues("spring.application.name=account-service");

    @Test
    void 서비스_이름을_제목으로_쓰고_JWT_인증을_등록한다() {
        contextRunner.run(context -> {
            OpenAPI openAPI = context.getBean(OpenAPI.class);

            assertThat(openAPI.getInfo().getTitle()).isEqualTo("account-service API");
            assertThat(openAPI.getComponents().getSecuritySchemes()).containsKey("JWT");
            assertThat(openAPI.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("JWT"));
            assertThat(openAPI.getServers()).extracting("url").containsExactly("/");
        });
    }

    @Test
    void 서비스가_OpenAPI_빈을_직접_만들면_그쪽을_사용한다() {
        contextRunner
                .withBean(OpenAPI.class, () -> new OpenAPI().info(new Info().title("custom")))
                .run(context -> assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("custom"));
    }
}
