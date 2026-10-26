package com.gongmoju.common.swagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 서비스의 {@code @ApiErrorCodes} 어노테이션에 붙이는 표시.
 * <p>
 * 표시된 어노테이션의 {@code common()}, {@code value()}에 적힌 ErrorCode를 Swagger 응답 예시로 그린다 ({@link ErrorCodeOperationCustomizer}).
 * 어노테이션 속성은 인터페이스 배열을 받을 수 없어서, {@code value()} 타입이 서비스 enum인 어노테이션은 서비스마다 하나씩 둔다.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ErrorCodeExamples {
}
