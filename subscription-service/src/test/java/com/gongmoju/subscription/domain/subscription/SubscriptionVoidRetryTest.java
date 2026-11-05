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

@DisplayName("청약 반환 재요청 (COMPENSATING, CANCEL_REQUESTED 응답 시간 초과)")
class SubscriptionVoidRetryTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final Ipo ipo = mock(Ipo.class);

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = CancelReason.class, names = {"CONFIRM_FAILED", "TIMEOUT"})
    @DisplayName("COMPENSATING 청약의 반환을 다시 요청하면 요청 횟수가 1 늘고 마지막 요청 시각이 갱신되며 상태와 해제 사유는 그대로다")
    void retryVoid_increasesAttemptsAndKeepsStatusAndReason_whenCompensating(CancelReason reason) {
        // given
        Subscription subscription = compensating(reason);
        ReflectionTestUtils.setField(subscription, "lastRequestedAt", Instant.parse("2026-10-27T00:00:00Z"));
        Instant before = Instant.now();

        // when
        subscription.retryVoid();

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.COMPENSATING);
        assertThat(subscription.getCancelReason()).isEqualTo(reason);
        assertThat(subscription.getAttempts()).isEqualTo(2);
        assertThat(subscription.getLastRequestedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("CANCEL_REQUESTED 청약의 반환을 다시 요청해도 요청 횟수만 늘고 상태와 해제 사유(USER_CANCEL)는 그대로다")
    void retryVoid_increasesAttemptsAndKeepsStatusAndReason_whenCancelRequested() {
        // given
        Subscription subscription = compensating(CancelReason.TIMEOUT);
        ReflectionTestUtils.setField(subscription, "status", SubscriptionStatus.CANCEL_REQUESTED);
        ReflectionTestUtils.setField(subscription, "cancelReason", CancelReason.USER_CANCEL);

        // when
        subscription.retryVoid();

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCEL_REQUESTED);
        assertThat(subscription.getCancelReason()).isEqualTo(CancelReason.USER_CANCEL);
        assertThat(subscription.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("여러 번 다시 요청하면 요청 횟수가 그만큼 계속 늘어난다")
    void retryVoid_accumulatesAttempts() {
        // given
        Subscription subscription = compensating(CancelReason.CONFIRM_FAILED);

        // when
        subscription.retryVoid();
        subscription.retryVoid();

        // then
        assertThat(subscription.getAttempts()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = {"COMPENSATING", "CANCEL_REQUESTED"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("COMPENSATING, CANCEL_REQUESTED가 아닌 청약의 반환을 다시 요청하려 하면 IllegalStateException이 나고 요청 횟수와 상태는 바뀌지 않는다")
    void retryVoid_throws_whenNotVoiding(SubscriptionStatus status) {
        // given
        Subscription subscription = compensating(CancelReason.CONFIRM_FAILED);
        ReflectionTestUtils.setField(subscription, "status", status);

        // when, then
        assertThatThrownBy(subscription::retryVoid).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("해제 사유가 없는 청약의 반환을 다시 요청하려 하면 IllegalStateException이 난다")
    void retryVoid_throws_whenCancelReasonMissing() {
        // given
        Subscription subscription = compensating(CancelReason.CONFIRM_FAILED);
        ReflectionTestUtils.setField(subscription, "cancelReason", null);

        // when, then
        assertThatThrownBy(subscription::retryVoid).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getAttempts()).isEqualTo(1);
    }

    private Subscription compensating(CancelReason reason) {
        Subscription subscription = Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(),
                UUID.randomUUID(), 100, 1_500_000, RECEIVED_AT);
        subscription.startCompensation(reason);
        return subscription;
    }
}
