package com.gongmoju.subscription.presentation.subscription;

import com.gongmoju.subscription.application.subscription.SubscribeCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

// UUID 필드를 String으로 받아야 형식 오류도 필드별 400 details로 내려간다 (UUID 타입이면 JSON 변환 오류라 details가 없다)
@Schema(description = "청약 신청 요청")
public record SubscriptionCreateRequest(
        @Schema(description = "클라이언트가 만든 멱등 요청 ID (UUID). 같은 값으로 다시 요청하면 같은 subscriptionId를 돌려준다",
                example = "3b8f0000-0000-4000-8000-000000000031")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = UUID_PATTERN, message = "UUID 형식이 아닙니다.")
        String requestId,

        @Schema(description = "청약할 공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = UUID_PATTERN, message = "UUID 형식이 아닙니다.")
        String ipoId,

        @Schema(description = "청약에 사용할 계좌 ID", example = "9d4e0000-0000-4000-8000-000000000002")
        @NotBlank(message = "필수값입니다.")
        @Pattern(regexp = UUID_PATTERN, message = "UUID 형식이 아닙니다.")
        String accountId,

        @Schema(description = "청약 수량. 종목별 최소 청약 수량 ~ 청약 한도 이내", example = "100")
        @NotNull(message = "필수값입니다.")
        Long requestedQuantity
) {

    static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    // 대문자 UUID도 받는다. UUID.fromString이 소문자 UUID로 바꾸므로 subscriptionId 계산 결과는 같다
    public SubscribeCommand toCommand() {
        return new SubscribeCommand(UUID.fromString(requestId), UUID.fromString(ipoId), UUID.fromString(accountId),
                requestedQuantity);
    }
}
