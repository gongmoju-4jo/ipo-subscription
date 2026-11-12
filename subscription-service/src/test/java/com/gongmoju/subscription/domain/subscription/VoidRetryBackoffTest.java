package com.gongmoju.subscription.domain.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 반환 재요청 대기 시간 계산(백오프)을 검증함. 조회의 SQL 식과 같은 값인지는 SubscriptionStaleVoidQueryTest(PostgreSQL)가 확인.
 */
@DisplayName("반환 재요청 대기 시간 계산 (VoidRetryBackoff)")
class VoidRetryBackoffTest {

    private final VoidRetryBackoff backoff = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofMinutes(5));

    @ParameterizedTest(name = "요청 횟수 {0} → 대기 {1}초")
    @CsvSource({"1, 30", "2, 60", "3, 120", "4, 240", "5, 300", "6, 300", "10, 300", "19, 300", "20, 300", "21, 300", "100, 300", "2147483647, 300"})
    @DisplayName("대기 시간은 요청 횟수 1일 때 초기 값이고 요청할 때마다 2배가 되며 상한(5분)을 넘지 않는다")
    void waitAfter_doublesUntilMax(int attempts, long expectedSeconds) {
        // given, when
        Duration wait = backoff.waitAfter(attempts);

        // then
        assertThat(wait).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @ParameterizedTest(name = "요청 횟수 {0}")
    @CsvSource({"0", "-1", "-100", "-2147483648"})
    @DisplayName("요청 횟수가 1 미만이면 1로 보고 초기 대기 시간을 돌려준다 (보상을 시작하면 요청 횟수가 1)")
    void waitAfter_treatsLessThanOneAsOne(int attempts) {
        // given, when, then
        assertThat(backoff.waitAfter(attempts)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("초기 대기 시간과 상한이 같으면 요청 횟수와 상관없이 같은 대기 시간이다 (고정 간격)")
    void waitAfter_isFixed_whenInitialEqualsMax() {
        // given
        VoidRetryBackoff fixed = new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofSeconds(30));

        // when, then
        assertThat(fixed.waitAfter(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(fixed.waitAfter(2)).isEqualTo(Duration.ofSeconds(30));
        assertThat(fixed.waitAfter(50)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("상한이 2의 거듭제곱 배수가 아니어도(25초) 상한을 넘지 않고 상한에서 멈춘다")
    void waitAfter_stopsAtMax_whenMaxIsNotPowerOfTwoMultiple() {
        // given
        VoidRetryBackoff other = new VoidRetryBackoff(Duration.ofSeconds(10), Duration.ofSeconds(25));

        // when, then
        assertThat(other.waitAfter(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(other.waitAfter(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(other.waitAfter(3)).isEqualTo(Duration.ofSeconds(25));
        assertThat(other.waitAfter(4)).isEqualTo(Duration.ofSeconds(25));
    }

    @Test
    @DisplayName("대기 시간이 매우 길게 설정되어도 곱셈이 넘치지 않고 상한을 돌려준다")
    void waitAfter_doesNotOverflow() {
        // given
        VoidRetryBackoff huge = new VoidRetryBackoff(Duration.ofDays(3650), Duration.ofDays(36500));

        // when, then
        assertThat(huge.waitAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofDays(36500));
        assertThat(huge.waitAfter(2)).isEqualTo(Duration.ofDays(7300));
    }

    @Test
    @DisplayName("SQL에 넘기는 초 값은 밀리초까지 정확한 초 단위 실수이다")
    void seconds_forSql() {
        // given
        VoidRetryBackoff other = new VoidRetryBackoff(Duration.ofMillis(1500), Duration.ofMinutes(5));

        // when, then
        assertThat(other.initialSeconds()).isEqualTo(1.5);
        assertThat(other.maxSeconds()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("초기 대기 시간이 0 이하이면 만들 수 없다")
    void constructor_rejectsNonPositiveInitial() {
        assertThatThrownBy(() -> new VoidRetryBackoff(Duration.ZERO, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoidRetryBackoff(Duration.ofSeconds(-1), Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoidRetryBackoff(null, Duration.ofMinutes(5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상한이 초기 대기 시간보다 작으면 만들 수 없다")
    void constructor_rejectsMaxLessThanInitial() {
        assertThatThrownBy(() -> new VoidRetryBackoff(Duration.ofSeconds(30), Duration.ofSeconds(29))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VoidRetryBackoff(Duration.ofSeconds(30), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
