package com.gongmoju.subscription.domain.ipo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("공모주 (접수 Consumer 방어 검증)")
class IpoTest {

    private static final Instant END_AT = Instant.parse("2026-10-27T07:00:00Z");

    @ParameterizedTest(name = "마감 {0}, 도착 {1}이면 {2}")
    @CsvSource({
            "2026-10-27T07:00:00Z, 2026-10-27T06:59:59.999Z, true",
            "2026-10-27T07:00:00Z, 2026-10-27T07:00:00Z, false",
            "2026-10-27T07:00:00Z, 2026-10-27T07:00:00.001Z, false"
    })
    @DisplayName("receivedAt이 마감 시각보다 앞이면 true, 같거나 이후면 false")
    void isBeforeSubscriptionEnd_comparesReceivedAtWithEnd(Instant endAt, Instant receivedAt, boolean expected) {
        // given
        Ipo ipo = ipoEndingAt(endAt);

        // when
        boolean before = ipo.isBeforeSubscriptionEnd(receivedAt);

        // then
        assertThat(before).isEqualTo(expected);
    }

    @ParameterizedTest(name = "증거금률 {0}이면 {1}")
    @CsvSource({"1, true", "1.00, true", "0.50, false", "0.99, false"})
    @DisplayName("증거금률이 100%(1)일 때만 true, 스케일이 달라도(1, 1.00) 같은 값으로 본다")
    void hasFullMarginRate_isTrueOnlyForOne(BigDecimal marginRate, boolean expected) {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "marginRate", marginRate);

        // when
        boolean full = ipo.hasFullMarginRate();

        // then
        assertThat(full).isEqualTo(expected);
    }

    @Test
    @DisplayName("증거금률 기본값은 100%이다")
    void hasFullMarginRate_isTrue_byDefault() {
        // given
        Ipo ipo = ipoEndingAt(END_AT);

        // when
        boolean full = ipo.hasFullMarginRate();

        // then
        assertThat(full).isTrue();
    }

    // 공모주 생성 메서드가 없으므로(시드 데이터만 사용) 같은 패키지의 `protected` 생성자와 리플렉션으로 생성함
    private Ipo ipoEndingAt(Instant endAt) {
        Ipo ipo = new Ipo();
        ReflectionTestUtils.setField(ipo, "subscriptionEndAt", endAt);
        return ipo;
    }
}
