package com.gongmoju.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 모든 서비스가 같은 코드, 메시지로 응답하는 공통 에러. 도메인 에러는 각 서비스의 enum에 둔다.
 * <p>
 * 404는 리소스마다 코드를 만들지 않고 RESOURCE_NOT_FOUND + message, field로 구분한다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INVALID_STATUS(HttpStatus.BAD_REQUEST, "잘못된 상태값입니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "이미 존재하는 리소스입니다."),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "업무 규칙에 맞지 않는 요청입니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수를 초과했습니다. 잠시 후 다시 시도해주세요."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    /** 다른 서비스 호출 실패 (5xx, 타임아웃, 연결 실패) */
    DEPENDENCY_FAILURE(HttpStatus.BAD_GATEWAY, "다른 서비스 호출에 실패했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus status;
    private final String message;
}
