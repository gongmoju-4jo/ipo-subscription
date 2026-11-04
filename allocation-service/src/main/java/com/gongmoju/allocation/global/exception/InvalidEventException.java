package com.gongmoju.allocation.global.exception;

/**
 * 다시 처리해도 결과가 같은 잘못된 이벤트(필수 값 누락, 형식 오류, 값 범위 오류). 재시도 없이 바로 DLT로 보낸다 (이벤트 명세 3-3).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
