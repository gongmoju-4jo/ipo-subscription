package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("청약 인출 재요청 (PENDING 응답 시간 초과)")
class SubscriptionReserveRetryTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final Ipo ipo = mock(Ipo.class);

    @Test
    @DisplayName("PENDING 청약의 인출을 다시 요청하면 요청 횟수가 1 늘고 마지막 요청 시각이 현재 시각으로 갱신되며 상태는 그대로 PENDING이다")
    void retryReserve_increasesAttemptsAndRefreshesLastRequestedAt() {
        // given
        Subscription subscription = pending();
        ReflectionTestUtils.setField(subscription, "lastRequestedAt", Instant.parse("2026-10-27T00:00:00Z"));
        Instant before = Instant.now();

        // when
        subscription.retryReserve();

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PENDING);
        assertThat(subscription.getAttempts()).isEqualTo(2);
        assertThat(subscription.getLastRequestedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("여러 번 다시 요청하면 요청 횟수가 그만큼 계속 늘어난다")
    void retryReserve_accumulatesAttempts() {
        // given
        Subscription subscription = pending();

        // when
        subscription.retryReserve();
        subscription.retryReserve();

        // then
        assertThat(subscription.getAttempts()).isEqualTo(3);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "PENDING", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PENDING이 아닌 청약의 인출을 다시 요청하려 하면 IllegalStateException이 나고 요청 횟수와 상태는 바뀌지 않는다")
    void retryReserve_throws_whenNotPending(SubscriptionStatus status) {
        // given
        Subscription subscription = pending();
        ReflectionTestUtils.setField(subscription, "status", status);

        // when, then
        assertThatThrownBy(subscription::retryReserve).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getAttempts()).isEqualTo(1);
    }

    @ParameterizedTest(name = "요청 횟수 {0}, 최대 {1} → {2}")
    @CsvSource({"1, 3, false", "2, 3, false", "3, 3, true", "4, 3, true", "1, 1, true"})
    @DisplayName("요청 횟수가 최대 횟수 이상이면 최대 횟수에 이른 것으로 본다")
    void hasReachedMaxAttempts_comparesWithMax(int attempts, int maxAttempts, boolean expected) {
        // given
        Subscription subscription = pending();
        ReflectionTestUtils.setField(subscription, "attempts", attempts);

        // when
        boolean reached = subscription.hasReachedMaxAttempts(maxAttempts);

        // then
        assertThat(reached).isEqualTo(expected);
    }

    private Subscription pending() {
        return Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(), UUID.randomUUID(),
                100, 1_500_000, RECEIVED_AT);
    }
}
