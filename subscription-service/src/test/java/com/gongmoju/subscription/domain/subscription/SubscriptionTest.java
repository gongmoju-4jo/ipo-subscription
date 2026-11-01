package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("청약 생성")
class SubscriptionTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final Ipo ipo = mock(Ipo.class);

    @Test
    @DisplayName("접수하면 PENDING 상태이고 거절 사유가 없으며 요청 내용이 그대로 담긴다")
    void accept_createsPendingSubscription() {
        // given

        // when
        Subscription subscription = Subscription.accept(subscriptionId, requestId, ipo, userId, accountId,
                100, 1_500_000, RECEIVED_AT);

        // then
        assertThat(subscription.getId()).isEqualTo(subscriptionId);
        assertThat(subscription.getRequestId()).isEqualTo(requestId);
        assertThat(subscription.getIpo()).isSameAs(ipo);
        assertThat(subscription.getUserId()).isEqualTo(userId);
        assertThat(subscription.getAccountId()).isEqualTo(accountId);
        assertThat(subscription.getRequestedQuantity()).isEqualTo(100);
        assertThat(subscription.getMarginAmount()).isEqualTo(1_500_000);
        assertThat(subscription.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(subscription.getRejectReason()).isNull();
        assertThat(subscription.getAttempts()).isZero();
    }

    @Test
    @DisplayName("거절하면 REJECTED 상태이고 거절 사유가 담기며 나머지 요청 내용은 접수와 같다")
    void reject_createsRejectedSubscriptionWithReason() {
        // given

        // when
        Subscription subscription = Subscription.reject(subscriptionId, requestId, ipo, userId, accountId,
                100, 1_500_000, RECEIVED_AT, RejectReason.ALREADY_SUBSCRIBED);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.REJECTED);
        assertThat(subscription.getRejectReason()).isEqualTo(RejectReason.ALREADY_SUBSCRIBED);
        assertThat(subscription.getId()).isEqualTo(subscriptionId);
        assertThat(subscription.getRequestId()).isEqualTo(requestId);
        assertThat(subscription.getMarginAmount()).isEqualTo(1_500_000);
        assertThat(subscription.getAttempts()).isZero();
    }
}
