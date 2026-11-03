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

@DisplayName("청약 보상 착수 (PENDING → COMPENSATING)")
class SubscriptionCompensationTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final Ipo ipo = mock(Ipo.class);

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CancelReason.class, names = {"CONFIRM_FAILED", "TIMEOUT"})
    @DisplayName("PENDING 청약의 보상을 시작하면 COMPENSATING이 되고 해제 사유가 저장되며 요청 횟수 1, 마지막 요청 시각이 갱신된다")
    void startCompensation_changesToCompensating(CancelReason reason) {
        // given
        Subscription subscription = pending();
        Instant before = Instant.now();
        ReflectionTestUtils.setField(subscription, "attempts", 3);
        ReflectionTestUtils.setField(subscription, "lastRequestedAt", Instant.parse("2026-10-27T00:00:00Z"));

        // when
        subscription.startCompensation(reason);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.COMPENSATING);
        assertThat(subscription.getCancelReason()).isEqualTo(reason);
        assertThat(subscription.getAttempts()).isEqualTo(1);
        assertThat(subscription.getLastRequestedAt()).isAfterOrEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PENDING이 아닌 청약의 보상을 시작하려 하면 IllegalStateException이 나고 상태와 해제 사유는 바뀌지 않는다")
    void startCompensation_throws_whenNotPending(SubscriptionStatus status) {
        // given
        Subscription subscription = pending();
        ReflectionTestUtils.setField(subscription, "status", status);

        // when, then
        assertThatThrownBy(() -> subscription.startCompensation(CancelReason.CONFIRM_FAILED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getCancelReason()).isNull();
    }

    @Test
    @DisplayName("해제 사유가 없으면 IllegalArgumentException이 나고 PENDING으로 남는다")
    void startCompensation_throws_whenReasonIsNull() {
        // given
        Subscription subscription = pending();

        // when, then
        assertThatThrownBy(() -> subscription.startCompensation(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(subscription.getCancelReason()).isNull();
    }

    @Test
    @DisplayName("투자자 취소(USER_CANCEL)는 보상 착수의 사유가 될 수 없어 IllegalArgumentException이 나고 PENDING으로 남는다")
    void startCompensation_throws_whenReasonIsUserCancel() {
        // given
        Subscription subscription = pending();

        // when, then
        assertThatThrownBy(() -> subscription.startCompensation(CancelReason.USER_CANCEL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(subscription.getCancelReason()).isNull();
    }

    private Subscription pending() {
        return Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(), UUID.randomUUID(),
                100, 1_500_000, RECEIVED_AT);
    }
}
