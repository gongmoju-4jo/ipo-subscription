package com.gongmoju.common.feign;

import com.gongmoju.common.response.ApiErrorDetail;
import lombok.Getter;

import java.util.List;

/**
 * 다른 서비스가 4xx로 거절한 요청 (잔고 부족, 대상 없음 등). 재시도하지 않는다.
 */
@Getter
public class FeignClientException extends RuntimeException {

    private final int status;
    private final String code;
    private final List<ApiErrorDetail> details;

    /**
     * 상대 서비스 4xx 응답으로 예외를 만든다.
     *
     * @param status  상대 서비스 응답 HTTP 상태
     * @param code    상대 서비스 오류 코드
     * @param message 상대 서비스 오류 메시지
     * @param details 상대 서비스 필드별 상세 사유
     */
    public FeignClientException(int status, String code, String message, List<ApiErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
