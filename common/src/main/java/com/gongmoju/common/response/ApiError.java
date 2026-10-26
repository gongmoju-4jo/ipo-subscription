package com.gongmoju.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 실패 응답의 error 객체. details는 비어 있으면 JSON에서 빠진다.
 *
 * @param code    오류 코드
 * @param message 오류 메시지
 * @param details 필드별 상세 사유
 */
@Schema(description = "오류 정보")
public record ApiError(
        @Schema(description = "오류 코드", example = "RESOURCE_NOT_FOUND")
        String code,
        @Schema(description = "오류 메시지", example = "요청한 리소스를 찾을 수 없습니다.")
        String message,
        @Schema(description = "필드별 상세 사유")
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<ApiErrorDetail> details
) {
}
