package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 반환 재요청 설정(saga.void-retry.*)의 값 검증과 대기 시간 계산 변환을 검증함.
 */
@DisplayName("반환 재요청 설정 (VoidRetryProperties)")
class VoidRetryPropertiesTest {

    private static final Duration INITIAL = Duration.ofSeconds(30);
    private static final Duration MAX = Duration.ofMinutes(5);

    @Test
    @DisplayName("설정 값으로 같은 초기 대기 시간과 상한의 대기 시간 계산을 만든다")
    void backoff_usesConfiguredIntervals() {
        // given
        VoidRetryProperties properties = new VoidRetryProperties(INITIAL, MAX, 5, 100);

        // when
        VoidRetryBackoff backoff = properties.backoff();

        // then
        assertThat(backoff).isEqualTo(new VoidRetryBackoff(INITIAL, MAX));
        assertThat(backoff.waitAfter(3)).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("초기 대기 시간이 0 이하이면 설정을 만들 수 없다")
    void constructor_rejectsNonPositiveInitialInterval() {
        assertThatThrownBy(() -> new VoidRetryProperties(Duration.ZERO, MAX, 5, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoidRetryProperties(Duration.ofSeconds(-1), MAX, 5, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상한이 초기 대기 시간보다 작으면 설정을 만들 수 없다")
    void constructor_rejectsMaxIntervalLessThanInitial() {
        assertThatThrownBy(() -> new VoidRetryProperties(INITIAL, Duration.ofSeconds(29), 5, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("알림 기준 횟수나 batch 크기가 1 미만이면 설정을 만들 수 없다")
    void constructor_rejectsLessThanOne() {
        assertThatThrownBy(() -> new VoidRetryProperties(INITIAL, MAX, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoidRetryProperties(INITIAL, MAX, 5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
