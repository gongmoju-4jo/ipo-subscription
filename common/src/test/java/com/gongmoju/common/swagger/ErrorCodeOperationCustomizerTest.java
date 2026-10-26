package com.gongmoju.common.swagger;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.exception.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeOperationCustomizerTest {

    private final ErrorCodeOperationCustomizer customizer = new ErrorCodeOperationCustomizer();

    @Test
    void 공통_코드와_서비스_코드를_상태별로_묶어_응답_예시로_추가한다() throws Exception {
        Operation operation = customizer.customize(new Operation(), handler("withErrorCodes"));

        assertThat(operation.getResponses()).containsOnlyKeys("400", "401", "409");
        MediaType conflict = operation.getResponses().get("409").getContent().get("application/json");
        assertThat(conflict.getExamples()).containsOnlyKeys("DUPLICATE_RESOURCE", "SAMPLE_CONFLICT");
        assertThat(conflict.getSchema().get$ref()).isEqualTo(ErrorCodeOperationCustomizer.API_RESPONSE_SCHEMA_REF);
        assertThat(operation.getResponses().get("409").getDescription()).contains("샘플 충돌");
    }

    @Test
    void 어노테이션이_없으면_응답을_바꾸지_않는다() throws Exception {
        Operation operation = customizer.customize(new Operation(), handler("withoutErrorCodes"));

        assertThat(operation.getResponses()).isNull();
    }

    private HandlerMethod handler(String name) throws NoSuchMethodException {
        return new HandlerMethod(new SampleController(), SampleController.class.getMethod(name));
    }

    @Getter
    @RequiredArgsConstructor
    enum SampleErrorCode implements ErrorCode {
        SAMPLE_CONFLICT(HttpStatus.CONFLICT, "샘플 충돌");

        private final HttpStatus status;
        private final String message;
    }

    @ErrorCodeExamples
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface SampleApiErrorCodes {

        CommonErrorCode[] common() default {};

        SampleErrorCode[] value() default {};
    }

    static class SampleController {

        @SampleApiErrorCodes(
                common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.DUPLICATE_RESOURCE},
                value = SampleErrorCode.SAMPLE_CONFLICT)
        public void withErrorCodes() {
        }

        public void withoutErrorCodes() {
        }
    }
}
