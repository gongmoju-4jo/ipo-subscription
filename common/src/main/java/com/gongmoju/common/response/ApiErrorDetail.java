package com.gongmoju.common.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 필드별 상세 사유.
 *
 * @param field  문제가 된 필드 이름
 * @param reason 사유
 */
@Schema(description = "필드별 상세 사유")
public record ApiErrorDetail(
        @Schema(description = "필드명", example = "productId")
        String field,
        @Schema(description = "사유", example = "존재하지 않거나 삭제된 상품입니다.")
        String reason
) {
}
