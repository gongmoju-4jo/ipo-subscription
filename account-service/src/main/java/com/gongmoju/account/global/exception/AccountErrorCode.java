package com.gongmoju.account.global.exception;

import com.gongmoju.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// account-service 도메인 에러 코드. 공통 에러로 표현할 수 없는 것만 추가하고 API 명세 오류 표에도 같이 적는다
// 예: INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "예수금이 부족합니다."),
@Getter
@RequiredArgsConstructor
public enum AccountErrorCode implements ErrorCode {
    // 로그인 ID 없음 또는 비밀번호 불일치 (구분하지 않고 같은 응답)
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "로그인 ID 또는 비밀번호가 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;
}
