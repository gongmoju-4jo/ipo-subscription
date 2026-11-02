package com.gongmoju.account.global.exception;

/**
 * 재처리해도 결과가 같은 잘못된 이벤트를 DLT로 보낸다.
 * 필수 값 누락, 형식 오류, 값 불일치 등
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
