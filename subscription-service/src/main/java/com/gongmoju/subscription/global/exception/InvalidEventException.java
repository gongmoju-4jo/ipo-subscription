package com.gongmoju.subscription.global.exception;

/**
 * 재처리해도 결과가 같은 잘못된 이벤트(필수 값 누락, 형식 오류, 값 불일치)에 사용함. 재시도 없이 즉시 DLT로 전송함 (이벤트 명세 3-3).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
