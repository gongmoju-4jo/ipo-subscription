package com.gongmoju.account.domain.margin;

import java.util.Objects;
import java.util.UUID;

// 증거금 인출 거절 내용. margin.reserve-failed로 발행한다 (필드는 이벤트 형식 v1.1)
// margin을 만들지 않으므로 marginId가 없다. accountId는 본문이 아니라 메시지 키로 쓴다
public record MarginReserveFailed(
        UUID subscriptionId,
        UUID accountId,
        MarginReserveFailReason reason
) {

    public MarginReserveFailed {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reason, "reason");
    }
}
