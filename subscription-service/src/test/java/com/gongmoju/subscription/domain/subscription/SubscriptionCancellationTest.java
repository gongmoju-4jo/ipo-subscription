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

@DisplayName("청약 취소 완료 (COMPENSATING, CANCEL_REQUESTED → CANCELLED)")
class SubscriptionCancellationTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final Ipo ipo = mock(Ipo.class);

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = {"COMPENSATING", "CANCEL_REQUESTED"})
    @DisplayName("COMPENSATING, CANCEL_REQUESTED 청약의 취소를 완료하면 CANCELLED가 되고 해제 사유는 유지되며 요청 횟수가 0이 된다")
    void completeCancellation_changesToCancelled(SubscriptionStatus status) {
        // given
        Subscription subscription = inStatus(status, CancelReason.CONFIRM_FAILED);
        ReflectionTestUtils.setField(subscription, "attempts", 2);

        // when
        subscription.completeCancellation();

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(subscription.getCancelReason()).isEqualTo(CancelReason.CONFIRM_FAILED);
        assertThat(subscription.getAttempts()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = {"COMPENSATING", "CANCEL_REQUESTED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("COMPENSATING, CANCEL_REQUESTED가 아닌 청약의 취소를 완료하려 하면 IllegalStateException이 나고 상태와 요청 횟수는 바뀌지 않는다")
    void completeCancellation_throws_whenStatusIsNotCancellable(SubscriptionStatus status) {
        // given
        Subscription subscription = inStatus(status, CancelReason.CONFIRM_FAILED);
        ReflectionTestUtils.setField(subscription, "attempts", 2);

        // when, then
        assertThatThrownBy(subscription::completeCancellation).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("해제 사유가 없는 COMPENSATING 청약의 취소를 완료하려 하면 IllegalStateException이 나고 COMPENSATING으로 남는다")
    void completeCancellation_throws_whenCancelReasonIsMissing() {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.COMPENSATING, null);

        // when, then
        assertThatThrownBy(subscription::completeCancellation).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.COMPENSATING);
    }

    private Subscription inStatus(SubscriptionStatus status, CancelReason cancelReason) {
        Subscription subscription = Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(),
                UUID.randomUUID(), 100, 1_500_000, RECEIVED_AT);
        ReflectionTestUtils.setField(subscription, "status", status);
        ReflectionTestUtils.setField(subscription, "cancelReason", cancelReason);
        return subscription;
    }
}
