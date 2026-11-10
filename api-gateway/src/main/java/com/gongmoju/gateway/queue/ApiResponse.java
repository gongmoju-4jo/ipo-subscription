package com.gongmoju.gateway.queue;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 게이트웨이가 직접 응답하는 대기열 API의 공통 응답. 서비스(common ApiResponse)와 같은 형식이다 (게이트웨이는 common을 쓰지 않는다).
 * <ul>
 *   <li>성공: {"success":true,"data":{...},"message":"..."} (data가 없으면 생략)</li>
 *   <li>실패: {"success":false,"error":{"code":"...","message":"...","details":[{"field":"...","reason":"..."}]}} (details가 없으면 생략)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, String message, ErrorBody error) {

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static ApiResponse<Void> failure(String code, String message, List<Detail> details) {
        return new ApiResponse<>(false, null, null, new ErrorBody(code, message, details == null || details.isEmpty() ? null : details));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    // 이름이 java.lang.Error와 겹치지 않게 ErrorBody로 둔다 (JSON 필드 이름은 레코드 구성요소 이름 error 그대로)
    public record ErrorBody(String code, String message, List<Detail> details) {
    }

    public record Detail(String field, String reason) {
    }
}
