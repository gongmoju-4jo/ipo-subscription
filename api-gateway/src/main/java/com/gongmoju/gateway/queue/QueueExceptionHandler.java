package com.gongmoju.gateway.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebInputException;

import java.util.List;

/**
 * 대기열 API 오류를 공통 오류 형식으로 바꾼다. 코드와 메시지는 서비스의 공통 에러 코드(CommonErrorCode)와 같다.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = QueueController.class)
class QueueExceptionHandler {

    // 경로의 ipoId가 UUID가 아님
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ApiResponse<Void>> invalidInput(ServerWebInputException e) {
        String field = e.getMethodParameter() == null ? null : e.getMethodParameter().getParameterName();
        List<ApiResponse.Detail> details = field == null ? List.of() : List.of(new ApiResponse.Detail(field, "UUID 형식이 아닙니다."));
        return ResponseEntity.badRequest()
                .body(ApiResponse.failure("VALIDATION_ERROR", "입력값이 올바르지 않습니다.", details));
    }

    // 토큰의 sub가 UUID가 아님 (account-service가 발급한 토큰이면 생기지 않는다)
    @ExceptionHandler(QueueController.InvalidUserIdException.class)
    ResponseEntity<ApiResponse<Void>> invalidUser() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.failure("UNAUTHORIZED", "로그인이 필요합니다.", null));
    }

    // 줄에도 없고 허가도 없음 (진입 전, 이탈 처리됨, 허가 만료)
    @ExceptionHandler(QueueEntryNotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(QueueEntryNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.failure("RESOURCE_NOT_FOUND", e.getMessage(), null));
    }

    // Redis 연결 실패, 응답 시간 초과. 대기열 없이 통과시키지 않고 막는다 (Redis 장애 시 차단)
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiResponse<Void>> redisUnavailable(DataAccessException e) {
        log.warn("대기열 저장소(Redis) 호출 실패: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.failure("SERVICE_UNAVAILABLE", "서비스를 이용할 수 없습니다. 잠시 후 다시 시도해주세요.", null));
    }

    // 그 밖의 예상하지 못한 오류. Spring 기본 오류 JSON 대신 공통 형식으로 내려주고, 원인은 로그로만 남긴다 (서비스 GlobalExceptionHandler와 같음)
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("대기열 API 처리 중 예상하지 못한 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다.", null));
    }
}
