package com.gongmoju.subscription.domain.ipo;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("공모주 (증거금률 확인, 마감, 배정 완료, 경쟁률)")
class IpoTest {

    private static final Instant END_AT = Instant.parse("2026-10-27T07:00:00Z");

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

    @Test
    @DisplayName("OPEN 공모주를 마감하면 CLOSED가 된다")
    void close_changesOpenToClosed() {
        // given
        Ipo ipo = ipoEndingAt(END_AT);

        // when
        ipo.close();

        // then
        assertThat(ipo.getStatus()).isEqualTo(IpoStatus.CLOSED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = IpoStatus.class, names = {"CLOSED", "ALLOCATED"})
    @DisplayName("OPEN이 아닌 공모주를 마감하면 INVALID_STATUS_TRANSITION이고 상태는 그대로다")
    void close_throws_whenNotOpen(IpoStatus status) {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "status", status);

        // when, then
        assertThatThrownBy(ipo::close)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SubscriptionErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(ipo.getStatus()).isEqualTo(status);
    }

    @Test
    @DisplayName("CLOSED 공모주의 배정을 완료하면 ALLOCATED가 된다")
    void finishAllocation_changesClosedToAllocated() {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "status", IpoStatus.CLOSED);

        // when
        ipo.finishAllocation();

        // then
        assertThat(ipo.getStatus()).isEqualTo(IpoStatus.ALLOCATED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = IpoStatus.class, names = {"OPEN", "ALLOCATED"})
    @DisplayName("CLOSED가 아닌 공모주의 배정을 완료하면 INVALID_STATUS_TRANSITION이고 상태는 그대로다")
    void finishAllocation_throws_whenNotClosed(IpoStatus status) {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "status", status);

        // when, then
        assertThatThrownBy(ipo::finishAllocation)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(SubscriptionErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(ipo.getStatus()).isEqualTo(status);
    }

    /**
     * 상태 전이만 보므로 등록 규칙(register)을 거치지 않고 같은 패키지의 {@code protected} 생성자와 리플렉션으로 생성함.
     */
    @ParameterizedTest(name = "신청 {0}주 / 물량 {1}주 = {2}")
    @CsvSource({
            "0, 100, 0.00",
            "1500, 100, 15.00",
            "1527, 100, 15.27",
            "1, 3, 0.33",
            "2, 3, 0.67",
            "1, 8, 0.13",
            "1, 400, 0.00",
            "50, 100, 0.50"
    })
    @DisplayName("경쟁률은 신청 수량 / 일반청약 물량이고 소수 둘째 자리에서 반올림(HALF_UP)한다")
    void competitionRate_dividesRequestedByRetailQuantity(long requestedQuantity, long retailQuantity, String expected) {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "retailQuantity", retailQuantity);

        // when
        BigDecimal rate = ipo.competitionRate(requestedQuantity);

        // then: 값과 자릿수(scale 2)까지 같음
        assertThat(rate).isEqualTo(new BigDecimal(expected));
    }

    @Test
    @DisplayName("신청 수량 합이 음수면 경쟁률을 계산하지 않는다")
    void competitionRate_throws_whenRequestedQuantityIsNegative() {
        // given
        Ipo ipo = ipoEndingAt(END_AT);
        ReflectionTestUtils.setField(ipo, "retailQuantity", 100L);

        // when, then
        assertThatThrownBy(() -> ipo.competitionRate(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Ipo ipoEndingAt(Instant endAt) {
        Ipo ipo = new Ipo();
        ReflectionTestUtils.setField(ipo, "subscriptionEndAt", endAt);
        return ipo;
    }
}
