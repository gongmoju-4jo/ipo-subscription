package com.gongmoju.subscription.domain.ipo;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiErrorDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DisplayName("공모주 등록 (F-18)")
class IpoRegisterTest {

    private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

    @Test
    @DisplayName("규칙을 지키면 받은 값 그대로 OPEN 공모주가 된다")
    void register_createsOpenIpo() {
        // given
        Values v = new Values();

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.getId()).isNull();
        assertThat(ipo.getName()).isEqualTo("테스트 공모주 A");
        assertThat(ipo.getOfferPrice()).isEqualTo(15_000);
        assertThat(ipo.getRetailQuantity()).isEqualTo(100_000);
        assertThat(ipo.getEqualQuantity()).isEqualTo(50_000);
        assertThat(ipo.getMinQuantity()).isEqualTo(10);
        assertThat(ipo.getMaxQuantity()).isEqualTo(10_000);
        assertThat(ipo.getMarginRate()).isEqualByComparingTo("1.00");
        assertThat(ipo.getSubscriptionStartAt()).isEqualTo(v.startAt);
        assertThat(ipo.getSubscriptionEndAt()).isEqualTo(v.endAt);
        assertThat(ipo.getRefundDate()).isEqualTo(v.refundDate);
        assertThat(ipo.getStatus()).isEqualTo(IpoStatus.OPEN);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "균등 물량이 정확히 절반           | 100 | 50",
            "홀수 물량에서 절반을 올린 값      | 101 | 51",
            "균등 물량이 일반청약 물량 전체    | 100 | 100",
            "1주 물량                          | 1   | 1"
    })
    @DisplayName("균등 물량은 일반청약 물량의 절반 이상, 전체 이하면 통과한다")
    void register_acceptsEqualQuantityInRange(String title, long retail, long equal) {
        // given
        Values v = new Values().with(x -> {
            x.retailQuantity = retail;
            x.equalQuantity = equal;
        });

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.getEqualQuantity()).isEqualTo(equal);
    }

    @Test
    @DisplayName("균등 물량 x 2가 long 범위를 넘어도 절반 이상이면 통과한다 (곱하지 않고 비교)")
    void register_acceptsEqualQuantity_whenDoubleOverflowsLong() {
        // given
        Values v = new Values().with(x -> {
            x.retailQuantity = Long.MAX_VALUE;
            x.equalQuantity = Long.MAX_VALUE / 2 + 1;
        });

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.getEqualQuantity()).isEqualTo(Long.MAX_VALUE / 2 + 1);
    }

    @Test
    @DisplayName("최대 수량이 최소 수량과 같으면 통과한다")
    void register_acceptsMaxEqualToMin() {
        // given
        Values v = new Values().with(x -> {
            x.minQuantity = 10;
            x.maxQuantity = 10;
        });

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.getMaxQuantity()).isEqualTo(10);
    }

    @ParameterizedTest(name = "증거금률 {0}")
    @ValueSource(strings = {"1", "1.00"})
    @DisplayName("증거금률은 소수 자릿수와 관계없이 1이면 통과한다")
    void register_acceptsFullMarginRate(String rate) {
        // given
        Values v = new Values().with(x -> x.marginRate = new BigDecimal(rate));

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.hasFullMarginRate()).isTrue();
    }

    @Test
    @DisplayName("청약 시작 시각이 이미 지났어도 마감 전이면 통과한다 (시연용으로 바로 청약 중 등록)")
    void register_acceptsStartInPast() {
        // given
        Values v = new Values().with(x -> {
            x.startAt = NOW.minusSeconds(3600);
            x.endAt = NOW.plusSeconds(1);
            x.refundDate = LocalDate.parse("2026-11-04");
        });

        // when
        Ipo ipo = v.register();

        // then
        assertThat(ipo.getSubscriptionStartAt()).isBefore(NOW);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "공모가 0                          | offerPrice",
            "일반청약 물량 0                   | retailQuantity",
            "균등 물량이 절반 미만 (100 중 49) | equalQuantity",
            "홀수 물량에서 절반 미만 (101 중 50) | equalQuantity",
            "균등 물량이 전체 초과             | equalQuantity",
            "최소 수량 0                       | minQuantity",
            "최대 수량이 최소 수량 미만        | maxQuantity",
            "증거금률 0.50                     | marginRate",
            "증거금률 1.01                     | marginRate",
            "마감 시각이 시작 시각과 같음      | subscriptionEndAt",
            "마감 시각이 지금과 같음           | subscriptionEndAt",
            "환불일이 마감일과 같음            | refundDate"
    })
    @DisplayName("규칙 하나를 어기면 VALIDATION_ERROR이고 details에 그 필드만 나온다")
    void register_throws_whenOneRuleBroken(String title, String field) {
        // given
        Values v = new Values().with(broken(title));

        // when, then
        assertThatThrownBy(v::register)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_ERROR);
                    assertThat(be.getDetails()).extracting(ApiErrorDetail::field).containsExactly(field);
                });
    }

    @ParameterizedTest(name = "마감 {0}, 환불일 {1} → {2}")
    @CsvSource({
            // 마감 11/4 23:30 KST (UTC 14:30)
            "2026-11-04T14:30:00Z, 2026-11-05, true",
            "2026-11-04T14:30:00Z, 2026-11-04, false",
            // 마감 11/5 00:30 KST (UTC로는 아직 11/4). UTC 날짜로 비교하면 11/5가 통과해 버림
            "2026-11-04T15:30:00Z, 2026-11-05, false",
            "2026-11-04T15:30:00Z, 2026-11-06, true"
    })
    @DisplayName("환불일은 마감 시각의 KST 날짜보다 뒤여야 한다 (DB 제약과 같은 기준)")
    void register_comparesRefundDateWithKstEndDate(Instant endAt, LocalDate refundDate, boolean accepted) {
        // given
        Values v = new Values().with(x -> {
            x.endAt = endAt;
            x.refundDate = refundDate;
        });

        // when, then
        if (accepted) {
            assertThat(v.register().getRefundDate()).isEqualTo(refundDate);
        } else {
            assertThatThrownBy(v::register)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getDetails())
                    .satisfies(d -> assertThat(d).extracting(ApiErrorDetail::field).containsExactly("refundDate"));
        }
    }

    @Test
    @DisplayName("규칙을 여러 개 어기면 details에 모두 나온다")
    void register_reportsAllBrokenRules() {
        // given
        Values v = new Values().with(x -> {
            x.offerPrice = 0;
            x.equalQuantity = 10;
            x.maxQuantity = 5;
            x.marginRate = new BigDecimal("0.50");
            x.endAt = NOW.minusSeconds(1);
            x.startAt = NOW.minusSeconds(10);
            x.refundDate = LocalDate.parse("2026-11-01");
        });

        // when, then
        assertThatThrownBy(v::register)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getDetails())
                .satisfies(d -> assertThat(d)
                        .extracting(ApiErrorDetail::field, ApiErrorDetail::reason)
                        .containsExactly(
                                tuple("offerPrice", "1 이상이어야 합니다."),
                                tuple("equalQuantity", "일반청약 물량의 절반 이상, 일반청약 물량 이하여야 합니다."),
                                tuple("maxQuantity", "최소 청약 수량 이상이어야 합니다."),
                                tuple("marginRate", "증거금률은 1.00(100%)만 등록할 수 있습니다."),
                                tuple("subscriptionEndAt", "현재 시각보다 뒤여야 합니다."),
                                tuple("refundDate", "청약 마감일(KST)보다 뒤여야 합니다.")));
    }

    // 실패 케이스 제목별로 기본값에서 바꿀 값
    private static Consumer<Values> broken(String title) {
        return switch (title) {
            case "공모가 0" -> x -> x.offerPrice = 0;
            case "일반청약 물량 0" -> x -> {
                x.retailQuantity = 0;
                x.equalQuantity = 0;
            };
            case "균등 물량이 절반 미만 (100 중 49)" -> x -> {
                x.retailQuantity = 100;
                x.equalQuantity = 49;
            };
            case "홀수 물량에서 절반 미만 (101 중 50)" -> x -> {
                x.retailQuantity = 101;
                x.equalQuantity = 50;
            };
            case "균등 물량이 전체 초과" -> x -> {
                x.retailQuantity = 100;
                x.equalQuantity = 101;
            };
            case "최소 수량 0" -> x -> x.minQuantity = 0;
            case "최대 수량이 최소 수량 미만" -> x -> {
                x.minQuantity = 10;
                x.maxQuantity = 9;
            };
            case "증거금률 0.50" -> x -> x.marginRate = new BigDecimal("0.50");
            case "증거금률 1.01" -> x -> x.marginRate = new BigDecimal("1.01");
            case "마감 시각이 시작 시각과 같음" -> x -> x.endAt = x.startAt;
            case "마감 시각이 지금과 같음" -> x -> {
                x.startAt = NOW.minusSeconds(60);
                x.endAt = NOW;
                x.refundDate = LocalDate.parse("2026-11-04");
            };
            case "환불일이 마감일과 같음" -> x -> x.refundDate = LocalDate.parse("2026-11-05");
            default -> throw new IllegalArgumentException(title);
        };
    }

    // 명세 3.6 요청 예시와 같은 기본값. 마감 11/5 16:00 KST
    private static class Values {
        String name = "테스트 공모주 A";
        long offerPrice = 15_000;
        long retailQuantity = 100_000;
        long equalQuantity = 50_000;
        long minQuantity = 10;
        long maxQuantity = 10_000;
        BigDecimal marginRate = new BigDecimal("1.00");
        Instant startAt = Instant.parse("2026-11-04T01:00:00Z");
        Instant endAt = Instant.parse("2026-11-05T07:00:00Z");
        LocalDate refundDate = LocalDate.parse("2026-11-07");

        Values with(Consumer<Values> change) {
            change.accept(this);
            return this;
        }

        Ipo register() {
            return Ipo.register(name, offerPrice, retailQuantity, equalQuantity, minQuantity, maxQuantity, marginRate,
                    startAt, endAt, refundDate, NOW);
        }
    }
}
