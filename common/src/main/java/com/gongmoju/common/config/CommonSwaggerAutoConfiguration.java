package com.gongmoju.common.config;

import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.swagger.ErrorCodeOperationCustomizer;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * springdoc을 쓰는 서비스에 공통 Swagger 설정(문서 제목, JWT Authorize, 오류 응답 예시)을 등록한다.
 * <p>
 * Swagger UI: http://localhost:{server.port}/swagger-ui/index.html (통합: http://localhost:8080/swagger-ui/index.html).
 * 서비스에서 OpenAPI 빈을 직접 만들면 그쪽을 사용한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.customizers.OperationCustomizer")
public class CommonSwaggerAutoConfiguration {

    private static final String JWT_SCHEME = "JWT";

    /**
     * 서비스 이름을 제목으로 쓰는 OpenAPI 문서 정보를 등록한다.
     *
     * @param applicationName 문서 제목에 쓸 서비스 이름 (spring.application.name)
     * @return OpenAPI 문서 정보
     */
    @Bean
    @ConditionalOnMissingBean(OpenAPI.class)
    public OpenAPI openAPI(@Value("${spring.application.name:service}") String applicationName) {
        // Authorize 버튼에 토큰만 입력하면 "Bearer "를 붙여 Authorization 헤더로 전송
        SecurityScheme bearerScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT");

        Components components = new Components().addSecuritySchemes(JWT_SCHEME, bearerScheme);
        // 오류 응답 예시(@ApiErrorCodes)가 참조할 수 있도록 ApiResponse 스키마를 등록
        ModelConverters.getInstance().read(ApiResponse.class).forEach(components::addSchemas);

        // 서버 주소를 상대경로로 두면 통합 Swagger에서는 게이트웨이(8080)로, 서비스 Swagger에서는 서비스 포트로 Try it out 요청이 간다
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info().title(applicationName + " API").version("v1"))
                .components(components)
                .addSecurityItem(new SecurityRequirement().addList(JWT_SCHEME));
    }

    /**
     * 컨트롤러 메서드의 {@code @ApiErrorCodes}를 응답 예시로 그리는 customizer를 등록한다.
     *
     * @return 오류 응답 예시 customizer
     */
    @Bean
    public OperationCustomizer errorCodeOperationCustomizer() {
        return new ErrorCodeOperationCustomizer();
    }
}
