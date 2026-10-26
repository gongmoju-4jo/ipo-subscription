package com.gongmoju.common.feign;

import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Feign 호출 실패 처리. GlobalExceptionHandler의 Exception 처리보다 먼저 적용되도록 순서를 앞에 둔다.
 * <p>
 * Feign이 있는 서비스에서만 CommonFeignAutoConfiguration이 등록한다.
 */
@Slf4j
@Order(Ordered.LOWEST_PRECEDENCE - 1)
@RestControllerAdvice
public class FeignExceptionHandler {

    /**
     * 다른 서비스가 4xx로 거절한 경우 그 상태, 코드, 메시지, details를 그대로 전달
     *
     * @param e 상대 서비스 4xx 예외
     * @return 상대 서비스와 같은 실패 응답
     */
    @ExceptionHandler(FeignClientException.class)
    public ResponseEntity<ApiResponse<Void>> handleFeignClientException(FeignClientException e) {
        return ResponseEntity
                .status(e.getStatus())
                .body(ApiResponse.fail(e.getCode(), e.getMessage(), e.getDetails()));
    }

    /**
     * 다른 서비스 5xx, 타임아웃, 연결 실패 (보상 처리하지 않고 올라온 경우)
     *
     * @param e FeignServerException 또는 RetryableException
     * @return DEPENDENCY_FAILURE(502) 응답
     */
    @ExceptionHandler({FeignServerException.class, RetryableException.class})
    public ResponseEntity<ApiResponse<Void>> handleFeignServerException(Exception e) {
        log.error("Feign call failed", e);
        CommonErrorCode errorCode = CommonErrorCode.DEPENDENCY_FAILURE;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }
}
