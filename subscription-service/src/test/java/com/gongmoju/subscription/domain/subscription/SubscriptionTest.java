package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        // 저장과 함께 증거금 확보 요청을 기록하므로 요청 횟수 1, 마지막 요청 시각이 있어야 함
        assertThat(subscription.getAttempts()).isEqualTo(1);
        assertThat(subscription.getLastRequestedAt()).isNotNull();
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
        // 증거금 확보 요청을 보내지 않으므로 요청 횟수 0, 마지막 요청 시각 없음
        assertThat(subscription.getAttempts()).isZero();
        assertThat(subscription.getLastRequestedAt()).isNull();
    }

    @ParameterizedTest(name = "수량 {0}")
    @ValueSource(longs = {0, -1})
    @DisplayName("청약 수량이 1보다 작으면 생성하지 못한다")
    void accept_throws_whenRequestedQuantityIsNotPositive(long requestedQuantity) {
        // given

        // when, then
        assertThatThrownBy(() -> Subscription.accept(subscriptionId, requestId, ipo, userId, accountId,
                requestedQuantity, 1_500_000, RECEIVED_AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "증거금 {0}")
    @ValueSource(longs = {0, -1})
    @DisplayName("증거금이 1보다 작으면 생성하지 못한다 (REJECTED도 같은 규칙)")
    void reject_throws_whenMarginAmountIsNotPositive(long marginAmount) {
        // given

        // when, then
        assertThatThrownBy(() -> Subscription.reject(subscriptionId, requestId, ipo, userId, accountId,
                100, marginAmount, RECEIVED_AT, RejectReason.ALREADY_SUBSCRIBED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("회원, 계좌, 종목, 수량이 같으면 같은 요청이다")
    void isSameRequest_returnsTrue_whenContentIsSame() {
        // given
        UUID ipoId = UUID.randomUUID();
        when(ipo.getId()).thenReturn(ipoId);
        Subscription subscription = Subscription.accept(subscriptionId, requestId, ipo, userId, accountId,
                100, 1_500_000, RECEIVED_AT);

        // when
        boolean same = subscription.isSameRequest(userId, accountId, ipoId, 100);

        // then
        assertThat(same).isTrue();
    }

    @ParameterizedTest(name = "{0}이 다르면 다른 요청")
    @ValueSource(strings = {"userId", "accountId", "ipoId", "requestedQuantity"})
    @DisplayName("회원, 계좌, 종목, 수량 중 하나라도 다르면 다른 요청이다")
    void isSameRequest_returnsFalse_whenAnyFieldDiffers(String differingField) {
        // given
        UUID ipoId = UUID.randomUUID();
        when(ipo.getId()).thenReturn(ipoId);
        Subscription subscription = Subscription.accept(subscriptionId, requestId, ipo, userId, accountId,
                100, 1_500_000, RECEIVED_AT);

        // when
        boolean same = subscription.isSameRequest(
                "userId".equals(differingField) ? UUID.randomUUID() : userId,
                "accountId".equals(differingField) ? UUID.randomUUID() : accountId,
                "ipoId".equals(differingField) ? UUID.randomUUID() : ipoId,
                "requestedQuantity".equals(differingField) ? 101 : 100);

        // then
        assertThat(same).isFalse();
    }
}
