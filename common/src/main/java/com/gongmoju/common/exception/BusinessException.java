package com.gongmoju.common.exception;

import com.gongmoju.common.response.ApiErrorDetail;
import lombok.Getter;

import java.util.List;

/**
 * 업무 규칙 위반 예외. GlobalExceptionHandler가 ErrorCode의 상태와 코드로 응답한다.
 * <p>
 * 예: {@code throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "존재하지 않거나 삭제된 상품입니다.", "productId");}
 */
@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<ApiErrorDetail> details;

    /**
     * 에러 코드의 기본 메시지로 예외를 만든다.
     *
     * @param errorCode 에러 코드. 메시지는 에러 코드의 기본 메시지
     */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), List.of());
    }

    /**
     * 메시지를 지정해 예외를 만든다.
     *
     * @param errorCode 에러 코드
     * @param message   기본 메시지 대신 내려줄 메시지
     */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    /**
     * 문제가 된 필드를 details로 함께 내려줄 때 사용
     *
     * @param errorCode 에러 코드
     * @param message   메시지 (details의 reason에도 같은 값)
     * @param field     문제가 된 필드 이름
     */
    public BusinessException(ErrorCode errorCode, String message, String field) {
        this(errorCode, message, List.of(new ApiErrorDetail(field, message)));
    }

    /**
     * 필드별 상세 사유를 포함해 예외를 만든다.
     *
     * @param errorCode 에러 코드
     * @param message   메시지
     * @param details   필드별 상세 사유
     */
    public BusinessException(ErrorCode errorCode, String message, List<ApiErrorDetail> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
