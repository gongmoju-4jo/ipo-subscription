package com.gongmoju.common.exception;

import com.gongmoju.common.response.ApiErrorDetail;
import com.gongmoju.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.List;

/**
 * 모든 서비스 공통 예외 처리. CommonWebAutoConfiguration이 등록한다.
 * <p>
 * 서비스에서 따로 처리하고 싶은 예외는 서비스에 {@code @RestControllerAdvice + @Order(Ordered.HIGHEST_PRECEDENCE)}로 추가한다.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE)
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String UNIQUE_VIOLATION = "23505";

    /**
     * 업무 규칙 위반: ErrorCode의 상태, 코드, 메시지, details로 응답
     *
     * @param e 업무 예외
     * @return 실패 응답
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        return toResponse(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    /**
     * {@code @Valid} 검증 실패: 실패한 필드를 모두 details로 내려준다
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        List<ApiErrorDetail> details = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new ApiErrorDetail(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();
        return toResponse(CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.VALIDATION_ERROR.getMessage(), details);
    }

    /**
     * JSON 형식 오류
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(CommonErrorCode.VALIDATION_ERROR);
    }

    /**
     * 경로/쿼리 파라미터 타입 불일치 (예: UUID 형식 오류)
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return toFieldResponse(e.getName(), "형식이 올바르지 않습니다.");
    }

    /**
     * 필수 쿼리 파라미터 누락
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException e) {
        return toFieldResponse(e.getParameterName(), "필수값입니다.");
    }

    /**
     * 필수 헤더 누락
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        return toFieldResponse(e.getHeaderName(), "필수 헤더입니다.");
    }

    /**
     * 존재하지 않는 필드로 정렬 요청 (예: ?sort=unknown,desc)
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiResponse<Void>> handlePropertyReference(PropertyReferenceException e) {
        return toFieldResponse("sort", "정렬할 수 없는 필드입니다: " + e.getPropertyName());
    }

    /**
     * 존재하지 않는 URL
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return toResponse(CommonErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 지원하지 않는 HTTP 메서드
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return toResponse(CommonErrorCode.METHOD_NOT_ALLOWED);
    }

    /**
     * DB 제약 위반. 동시 요청으로 유니크 인덱스에 걸린 경우만 409, 나머지(NOT NULL, FK 등)는 500
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        if (isUniqueViolation(e)) {
            return toResponse(CommonErrorCode.DUPLICATE_RESOURCE);
        }
        log.error("Data integrity violation", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 처리되지 않은 모든 예외
     *
     * @param e 처리할 예외
     * @return 실패 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return toResponse(CommonErrorCode.INTERNAL_SERVER_ERROR);
    }

    // PostgreSQL unique_violation = SQLState 23505
    private boolean isUniqueViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ApiResponse<Void>> toFieldResponse(String field, String reason) {
        return toResponse(CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.VALIDATION_ERROR.getMessage(),
                List.of(new ApiErrorDetail(field, reason)));
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode) {
        return toResponse(errorCode, errorCode.getMessage(), List.of());
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(ErrorCode errorCode, String message, List<ApiErrorDetail> details) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode, message, details));
    }
}
