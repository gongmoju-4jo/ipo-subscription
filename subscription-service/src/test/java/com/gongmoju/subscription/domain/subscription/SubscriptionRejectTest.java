package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("청약 거절 (증거금 확보 실패)")
class SubscriptionRejectTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final Ipo ipo = mock(Ipo.class);

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RejectReason.class, names = {"INSUFFICIENT_BALANCE", "ACCOUNT_NOT_FOUND"})
    @DisplayName("PENDING 청약을 거절하면 REJECTED가 되고 거절 사유가 저장되며 요청 횟수가 0으로 초기화된다")
    void rejectByMarginFailure_changesToRejectedAndStoresReason(RejectReason reason) {
        // given
        Subscription subscription = pending();

        // when
        subscription.rejectByMarginFailure(reason);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.REJECTED);
        assertThat(subscription.getRejectReason()).isEqualTo(reason);
        assertThat(subscription.getAttempts()).isZero();
        assertThat(subscription.getMarginId()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PENDING이 아닌 청약을 거절하려 하면 IllegalStateException이 나고 상태와 거절 사유는 바뀌지 않는다")
    void rejectByMarginFailure_throws_whenNotPending(SubscriptionStatus status) {
        // given
        Subscription subscription = pending();
        ReflectionTestUtils.setField(subscription, "status", status);

        // when, then
        assertThatThrownBy(() -> subscription.rejectByMarginFailure(RejectReason.INSUFFICIENT_BALANCE))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getRejectReason()).isNull();
    }

    @Test
    @DisplayName("거절 사유가 없거나 이중청약(ALREADY_SUBSCRIBED)이면 IllegalArgumentException이 나고 PENDING으로 남는다")
    void rejectByMarginFailure_throws_whenReasonIsInvalid() {
        // given
        Subscription subscription = pending();

        // when, then
        assertThatThrownBy(() -> subscription.rejectByMarginFailure(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscription.rejectByMarginFailure(RejectReason.ALREADY_SUBSCRIBED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(subscription.getRejectReason()).isNull();
    }

    private Subscription pending() {
        return Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(), UUID.randomUUID(),
                100, 1_500_000, RECEIVED_AT);
    }
}
