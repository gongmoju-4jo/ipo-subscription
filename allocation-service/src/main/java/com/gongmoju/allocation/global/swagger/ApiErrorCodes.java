package com.gongmoju.allocation.global.swagger;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.swagger.ErrorCodeExamples;
import com.gongmoju.allocation.global.exception.AllocationErrorCode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 컨트롤러 메서드에서 발생할 수 있는 에러를 Swagger 응답 예시로 노출한다 (common ErrorCodeOperationCustomizer가 그림)
// 예: @ApiErrorCodes(common = {VALIDATION_ERROR, UNAUTHORIZED}, value = {...})
@ErrorCodeExamples
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    CommonErrorCode[] common() default {};

    AllocationErrorCode[] value() default {};
}
