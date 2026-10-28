package com.gongmoju.account.presentation.account;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "계좌 개설 요청")
public record AccountOpenRequest(
        @Schema(description = "클라이언트가 만든 요청 ID (UUID). 같은 값으로 다시 요청하면 계좌를 새로 만들지 않고 처음 결과를 반환",
                example = "3b8f0000-0000-4000-8000-000000000021")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "UUID 형식이 아닙니다.")
        String requestId
) {
}
