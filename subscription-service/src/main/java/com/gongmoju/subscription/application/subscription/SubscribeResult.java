package com.gongmoju.subscription.application.subscription;

import java.util.UUID;

/**
 * 청약 신청 접수 결과. 접수만 끝난 상태라 DB에는 아직 청약 행이 없을 수 있다.
 */
public record SubscribeResult(
        UUID subscriptionId,
        UUID requestId
) {
}
