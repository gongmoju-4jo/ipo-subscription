package com.gongmoju.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 공통, 도메인 에러 코드 enum이 구현한다. {@link #name()}은 enum 상수 이름이 그대로 쓰인다.
 */
public interface ErrorCode {

    /**
     * 오류 코드 이름
     *
     * @return 응답의 error.code 값 (enum 상수 이름)
     */
    String name();

    /**
     * 응답 상태
     *
     * @return 응답 HTTP 상태
     */
    HttpStatus getStatus();

    /**
     * 기본 메시지
     *
     * @return 기본 메시지
     */
    String getMessage();
}
