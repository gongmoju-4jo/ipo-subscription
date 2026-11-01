package com.gongmoju.account.presentation.account;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gongmoju.account.presentation.support.StrictLongDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(description = "가상 입금 요청")
public record DepositRequest(
        @Schema(description = "클라이언트가 만든 요청 ID (UUID). 같은 값으로 다시 요청하면 입금을 다시 하지 않고 처음 결과를 반환",
                example = "3b8f0000-0000-4000-8000-000000000020")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                message = "UUID 형식이 아닙니다.")
        String requestId,
        @Schema(description = "입금액 (원, 1 이상의 정수). 1회 상한은 미정", example = "1000000")
        // 1.5가 1로 내림되거나 "100"이 100으로 바뀌지 않도록 정수 숫자만 받는다
        @JsonDeserialize(using = StrictLongDeserializer.class)
        @NotNull(message = "필수값입니다.")
        @Min(value = 1, message = "1 이상이어야 합니다.")
        Long amount
) {
}
