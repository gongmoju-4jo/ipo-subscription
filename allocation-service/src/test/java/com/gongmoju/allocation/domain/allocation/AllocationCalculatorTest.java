package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationCalculation.SubscriptionAllocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationCalculatorTest {

    private static final long OFFER_PRICE = 10_000;
    private static final long MIN_QUANTITY = 10;

    private final AllocationCalculator calculator = new AllocationCalculator();

    // 균등은 똑같이 나누고 나머지 물량은 남은 청약 수량에 비례해서 나눈다
    @Test
    void splitsEqualEvenlyAndRestByRemainingDemand() {
        // 균등 25 / 25, 비례 50을 (45 - 25) : (105 - 25) = 20 : 80으로
        AllocationCalculation result = calculate(100, 50, 45, 105);

        assertQuantities(result, new long[]{25, 25}, new long[]{10, 40});
        assertThat(result.fractionalShareQuantity()).isZero();
        assertThat(result.undersubscribedQuantity()).isZero();
        assertThat(result.totalAllocatedQuantity()).isEqualTo(100);
    }

    // 균등 몫의 나머지는 비례로 넘긴다
    @Test
    void movesEqualRemainderToProportional() {
        // TODO 추첨 구현 후에는 나머지 2주를 추첨으로 1주씩 배정
        // 균등 50 / 3 = 16 (나머지 2) → 비례 물량 100 - 48 = 52
        AllocationCalculation result = calculate(100, 50, 100, 100, 100);

        assertQuantities(result, new long[]{16, 16, 16}, new long[]{17, 17, 17});
        assertThat(result.fractionalShareQuantity()).isEqualTo(1);
    }

    // 균등 몫이 청약 수량보다 크면 청약 수량까지만 주고 잘린 몫은 비례로 넘긴다
    @Test
    void capsEqualShareAtRequestedQuantity() {
        // 균등 몫 16, A는 10주만 신청 → 균등 10 / 16 / 16, 비례 물량 100 - 42 = 58
        // 비례 기준 0 / 4 / 184 → 0 / 1.23 / 56.77
        AllocationCalculation result = calculate(100, 50, 10, 20, 200);

        assertQuantities(result, new long[]{10, 16, 16}, new long[]{0, 1, 56});
        assertThat(allocatedQuantities(result)).containsExactly(10L, 17L, 72L);
        assertThat(result.fractionalShareQuantity()).isEqualTo(1);
        assertThat(result.undersubscribedQuantity()).isZero();
    }

    // 청약이 균등 물량보다 많으면 균등 몫은 0이고 전부 비례로 나눈다
    @Test
    void givesNoEqualShareWhenSubscriptionsExceedEqualQuantity() {
        // TODO 추첨 구현 후에는 균등 물량을 추첨으로 1주씩 배정
        AllocationCalculation result = calculate(10, 2, 10, 10, 20);

        assertQuantities(result, new long[]{0, 0, 0}, new long[]{2, 2, 5});
        assertThat(result.fractionalShareQuantity()).isEqualTo(1);
    }

    // 최소 청약 수량보다 적게 신청한 청약은 균등에서 빠지고 비례만 받는다
    @Test
    void excludesBelowMinQuantityFromEqual() {
        // 균등 대상 B 1건 → 균등 0 / 50, 비례 50을 5 : 50으로
        AllocationCalculation result = calculate(100, 50, 5, 100);

        assertQuantities(result, new long[]{0, 50}, new long[]{4, 45});
        assertThat(result.fractionalShareQuantity()).isEqualTo(1);
    }

    // 균등 대상이 없으면 균등 물량도 비례로 나눈다
    @Test
    void movesEqualQuantityToProportionalWhenNoEqualTarget() {
        // 최소 청약 수량 100, 모두 100 미만 → 비례 물량 100을 30 : 50 : 70으로 → 20 / 33.33 / 46.67
        AllocationCalculation result = calculator.calculate(OFFER_PRICE, 100, 50, 100, subscriptions(30, 50, 70));

        assertQuantities(result, new long[]{0, 0, 0}, new long[]{20, 33, 46});
        assertThat(result.fractionalShareQuantity()).isEqualTo(1);
        assertThat(result.undersubscribedQuantity()).isZero();
    }

    // 청약 미달이면 모두 청약 수량만큼 받고 남는 수량은 배정하지 않는다
    @Test
    void allocatesRequestedQuantityWhenUndersubscribed() {
        AllocationCalculation result = calculate(100, 50, 30, 40);

        assertQuantities(result, new long[]{25, 25}, new long[]{5, 15});
        assertThat(result.totalAllocatedQuantity()).isEqualTo(70);
        assertThat(result.undersubscribedQuantity()).isEqualTo(30);
        assertThat(result.fractionalShareQuantity()).isZero();
    }

    // 청약 수량 합이 물량과 같으면 모두 청약 수량만큼 받는다
    @Test
    void allocatesRequestedQuantityWhenTotalRequestedEqualsRetail() {
        AllocationCalculation result = calculate(100, 50, 40, 60);

        assertThat(allocatedQuantities(result)).containsExactly(40L, 60L);
        assertThat(result.undersubscribedQuantity()).isZero();
        assertThat(result.fractionalShareQuantity()).isZero();
    }

    // 청약이 1건이면 균등과 비례를 모두 받는다
    @Test
    void allocatesEqualAndProportionalToSingleSubscription() {
        AllocationCalculation result = calculate(100, 50, 200);

        assertQuantities(result, new long[]{50}, new long[]{50});
    }

    // 청약이 없으면 물량 전부가 미달로 남는다
    @Test
    void leavesRetailQuantityUndersubscribedWhenNoSubscriptions() {
        AllocationCalculation result = calculate(100, 50);

        assertThat(result.allocations()).isEmpty();
        assertThat(result.undersubscribedQuantity()).isEqualTo(100);
        assertThat(result.fractionalShareQuantity()).isZero();
    }

    // 배정 대금은 배정 수량 x 공모가이고 0주도 결과에 포함한다
    @Test
    void calculatesAllocatedAmountAndIncludesZeroAllocation() {
        // 균등 몫 0, 비례 10을 10 : 10 : 100으로 → 0.83 / 0.83 / 8.33
        AllocationCalculation result = calculate(10, 2, 10, 10, 100);

        assertThat(result.allocations()).hasSize(3);
        assertThat(allocatedQuantities(result)).containsExactly(0L, 0L, 8L);
        assertThat(result.allocations())
                .allSatisfy(allocation -> assertThat(allocation.allocatedAmount())
                        .isEqualTo(allocation.allocatedQuantity() * OFFER_PRICE));
    }

    // 배정 대금이 long 범위를 넘으면 예외가 난다
    @Test
    void throwsWhenAllocatedAmountOverflows() {
        assertThatThrownBy(() -> calculator.calculate(
                Long.MAX_VALUE / 2, 100, 50, MIN_QUANTITY, subscriptions(200)))
                .isInstanceOf(ArithmeticException.class);
    }

    // 비례 계산의 중간 곱이 long 범위를 넘어도 결과가 long에 들어가면 계산한다
    @Test
    void calculatesProportionalWhenIntermediateProductOverflowsLong() {
        // 비례 물량 40억 x 비례 기준 30억 = 1.2 x 10^19 > Long.MAX_VALUE. 공모가 1원은 배정 대금이 넘치지 않게
        AllocationCalculation result = calculator.calculate(
                1, 4_000_000_000L, 0, 1, subscriptions(3_000_000_000L, 3_000_000_000L));

        assertQuantities(result, new long[]{0, 0}, new long[]{2_000_000_000L, 2_000_000_000L});
        assertThat(result.fractionalShareQuantity()).isZero();
    }

    // 어떤 입력이든 청약 수량을 넘지 않고 물량을 남김없이 넘치지 않게 나눈다
    @Test
    void keepsInvariantsForRandomInputs() {
        Random random = new Random(42);
        for (int round = 0; round < 1_000; round++) {
            long retailQuantity = 1 + random.nextInt(100_000);
            long equalQuantity = retailQuantity / 2 + random.nextLong(retailQuantity - retailQuantity / 2 + 1);
            long[] requested = new long[random.nextInt(200)];
            for (int i = 0; i < requested.length; i++) {
                requested[i] = 1 + random.nextInt(5_000);
            }
            List<ConfirmedSubscription> subscriptions = subscriptions(requested);

            AllocationCalculation result = calculator.calculate(
                    OFFER_PRICE, retailQuantity, equalQuantity, MIN_QUANTITY, subscriptions);

            for (SubscriptionAllocation allocation : result.allocations()) {
                assertThat(allocation.equalAllocatedQuantity()).isNotNegative();
                assertThat(allocation.proportionalAllocatedQuantity()).isNotNegative();
                assertThat(allocation.allocatedQuantity())
                        .isEqualTo(allocation.equalAllocatedQuantity() + allocation.proportionalAllocatedQuantity())
                        .isLessThanOrEqualTo(allocation.subscription().requestedQuantity());
                assertThat(allocation.allocatedAmount()).isEqualTo(allocation.allocatedQuantity() * OFFER_PRICE);
            }
            assertThat(result.totalAllocatedQuantity() + result.fractionalShareQuantity()
                    + result.undersubscribedQuantity()).isEqualTo(retailQuantity);
            if (Arrays.stream(requested).sum() <= retailQuantity) {
                // 청약 미달: 모두 청약 수량만큼, 단주 없음
                assertThat(result.allocations()).allSatisfy(allocation -> assertThat(allocation.allocatedQuantity())
                        .isEqualTo(allocation.subscription().requestedQuantity()));
                assertThat(result.fractionalShareQuantity()).isZero();
            } else {
                assertThat(result.undersubscribedQuantity()).isZero();
            }
            // 단주는 1주 미만 끝수의 합이라 청약 건수보다 적다
            assertThat(result.fractionalShareQuantity()).isLessThan(Math.max(1, requested.length));
        }
    }

    // 잘못된 입력은 거부한다
    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> calculator.calculate(0, 100, 50, MIN_QUANTITY, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, 101, MIN_QUANTITY, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, -1, MIN_QUANTITY, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, 50, MIN_QUANTITY, subscriptions(0)))
                .isInstanceOf(IllegalArgumentException.class);

        ConfirmedSubscription subscription = subscriptions(10).get(0);
        assertThatThrownBy(() -> calculator.calculate(
                OFFER_PRICE, 100, 50, MIN_QUANTITY, List.of(subscription, subscription)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AllocationCalculation calculate(long retailQuantity, long equalQuantity, long... requested) {
        return calculator.calculate(OFFER_PRICE, retailQuantity, equalQuantity, MIN_QUANTITY, subscriptions(requested));
    }

    // subscriptionId를 입력 순서대로 커지게 만든다 (subscriptionId 순 정렬을 넣어도 결과 순서가 같도록)
    private static List<ConfirmedSubscription> subscriptions(long... requested) {
        List<ConfirmedSubscription> subscriptions = new ArrayList<>();
        for (int i = 0; i < requested.length; i++) {
            subscriptions.add(new ConfirmedSubscription(
                    new UUID(0, i + 1), UUID.randomUUID(), UUID.randomUUID(), requested[i]));
        }
        return subscriptions;
    }

    private static void assertQuantities(AllocationCalculation result, long[] equal, long[] proportional) {
        assertThat(result.allocations()).extracting(SubscriptionAllocation::equalAllocatedQuantity)
                .containsExactly(boxed(equal));
        assertThat(result.allocations()).extracting(SubscriptionAllocation::proportionalAllocatedQuantity)
                .containsExactly(boxed(proportional));
    }

    private static List<Long> allocatedQuantities(AllocationCalculation result) {
        return result.allocations().stream().map(SubscriptionAllocation::allocatedQuantity).toList();
    }

    private static Long[] boxed(long[] values) {
        return Arrays.stream(values).boxed().toArray(Long[]::new);
    }
}
