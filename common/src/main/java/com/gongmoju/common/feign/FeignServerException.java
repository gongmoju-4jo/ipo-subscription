package com.gongmoju.common.feign;

import lombok.Getter;

/**
 * 다른 서비스의 5xx 응답. 재시도 또는 보상 처리 대상이다.
 * <p>
 * 보상 처리 시 타임아웃(feign.RetryableException)도 함께 잡는다: {@code catch (FeignServerException | RetryableException e)}
 */
@Getter
public class FeignServerException extends RuntimeException {

    private final int status;
    private final String code;

    /**
     * 상대 서비스 5xx 응답으로 예외를 만든다.
     *
     * @param status    상대 서비스 응답 HTTP 상태
     * @param code      상대 서비스 오류 코드
     * @param message   상대 서비스 오류 메시지
     * @param methodKey 호출한 Feign 메서드 (예외 메시지에 포함)
     */
    public FeignServerException(int status, String code, String message, String methodKey) {
        super(methodKey + " 호출 실패 (" + status + "): " + message);
        this.status = status;
        this.code = code;
    }
}
