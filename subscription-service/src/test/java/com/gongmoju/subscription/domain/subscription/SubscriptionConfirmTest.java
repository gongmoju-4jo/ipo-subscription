package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("청약 확정 (증거금 확보 성공)")
class SubscriptionConfirmTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");
    private static final Instant RESERVED_AT = Instant.parse("2026-10-27T06:59:58.900Z");

    private final Ipo ipo = mock(Ipo.class);
    private final UUID marginId = UUID.randomUUID();

    @Test
    @DisplayName("PENDING 청약을 확정하면 CONFIRMED가 되고 증거금 ID와 확정 시각이 저장되며 요청 횟수가 0으로 초기화된다")
    void confirm_changesToConfirmedAndStoresMarginInfo() {
        // given
        Subscription subscription = pending();

        // when
        subscription.confirm(marginId, RESERVED_AT);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(subscription.getMarginId()).isEqualTo(marginId);
        assertThat(subscription.getConfirmedAt()).isEqualTo(RESERVED_AT);
        assertThat(subscription.getAttempts()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PENDING이 아닌 청약을 확정하려 하면 IllegalStateException이 나고 상태는 바뀌지 않는다")
    void confirm_throws_whenNotPending(SubscriptionStatus status) {
        // given
        Subscription subscription = pending();
        org.springframework.test.util.ReflectionTestUtils.setField(subscription, "status", status);

        // when, then
        assertThatThrownBy(() -> subscription.confirm(marginId, RESERVED_AT)).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getMarginId()).isNull();
    }

    @Test
    @DisplayName("증거금 ID나 확보 시각이 없으면 IllegalArgumentException이 나고 PENDING으로 남는다")
    void confirm_throws_whenMarginIdOrReservedAtIsNull() {
        // given
        Subscription subscription = pending();

        // when, then
        assertThatThrownBy(() -> subscription.confirm(null, RESERVED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subscription.confirm(marginId, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
    }

    private Subscription pending() {
        return Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(), UUID.randomUUID(),
                100, 1_500_000, RECEIVED_AT);
    }
}
