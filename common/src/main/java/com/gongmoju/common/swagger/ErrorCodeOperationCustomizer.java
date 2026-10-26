package com.gongmoju.common.swagger;

import com.gongmoju.common.exception.ErrorCode;
import com.gongmoju.common.response.ApiResponse;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 컨트롤러 메서드의 {@code @ApiErrorCodes}(= {@link ErrorCodeExamples}가 붙은 어노테이션)에 적힌 ErrorCode를
 * HTTP 상태별로 묶어 Swagger 응답 예시로 추가한다.
 */
public class ErrorCodeOperationCustomizer implements OperationCustomizer {

    /** CommonSwaggerAutoConfiguration이 components에 등록하는 공통 응답 스키마 */
    public static final String API_RESPONSE_SCHEMA_REF = "#/components/schemas/ApiResponse";

    private static final String[] ERROR_CODE_ATTRIBUTES = {"common", "value"};

    /**
     * 메서드에 적힌 ErrorCode를 응답 예시로 추가한다.
     *
     * @param operation     Swagger 오퍼레이션
     * @param handlerMethod 컨트롤러 메서드
     * @return 응답 예시가 추가된 오퍼레이션
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        List<ErrorCode> errorCodes = collectErrorCodes(handlerMethod);
        if (errorCodes.isEmpty()) {
            return operation;
        }
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }

        Map<Integer, List<ErrorCode>> byStatus = errorCodes.stream()
                .collect(Collectors.groupingBy(errorCode -> errorCode.getStatus().value(), TreeMap::new, Collectors.toList()));
        byStatus.forEach((status, codes) ->
                operation.getResponses().addApiResponse(String.valueOf(status), toApiResponse(codes)));
        return operation;
    }

    // @ErrorCodeExamples가 붙은 어노테이션의 common(), value()를 읽는다
    private List<ErrorCode> collectErrorCodes(HandlerMethod handlerMethod) {
        List<ErrorCode> errorCodes = new ArrayList<>();
        for (Annotation annotation : handlerMethod.getMethod().getAnnotations()) {
            if (!annotation.annotationType().isAnnotationPresent(ErrorCodeExamples.class)) {
                continue;
            }
            for (String attribute : ERROR_CODE_ATTRIBUTES) {
                errorCodes.addAll(read(annotation, attribute));
            }
        }
        return errorCodes;
    }

    private List<ErrorCode> read(Annotation annotation, String attribute) {
        try {
            Object value = annotation.annotationType().getMethod(attribute).invoke(annotation);
            if (value instanceof ErrorCode[] codes) {
                return Arrays.asList(codes);
            }
            return List.of();
        } catch (NoSuchMethodException e) {
            return List.of();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(annotation.annotationType().getSimpleName() + "." + attribute + "()를 읽을 수 없습니다.", e);
        }
    }

    private io.swagger.v3.oas.models.responses.ApiResponse toApiResponse(List<ErrorCode> errorCodes) {
        MediaType mediaType = new MediaType().schema(new Schema<>().$ref(API_RESPONSE_SCHEMA_REF));
        errorCodes.forEach(errorCode -> mediaType.addExamples(errorCode.name(), new Example()
                .summary(errorCode.getMessage())
                .value(ApiResponse.fail(errorCode))));

        String description = errorCodes.stream()
                .map(ErrorCode::getMessage)
                .collect(Collectors.joining(" / "));

        return new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description)
                .content(new Content().addMediaType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE, mediaType));
    }
}
