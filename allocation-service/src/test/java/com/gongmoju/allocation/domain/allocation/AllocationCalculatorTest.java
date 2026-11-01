package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationCalculation.SubscriptionAllocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationCalculatorTest {

    private static final long OFFER_PRICE = 10_000;
    private static final long MIN_QUANTITY = 10;
    private static final long LOTTERY_SEED = 42;

    private final AllocationCalculator calculator = new AllocationCalculator();

    // 균등은 똑같이 나누고 나머지 물량은 남은 청약 수량에 비례해서 나눈다
    @Test
    void splitsEqualEvenlyAndRestByRemainingDemand() {
        // 균등 25 / 25, 비례 50을 (45 - 25) : (105 - 25) = 20 : 80으로
        AllocationCalculation result = calculate(100, 50, 45, 105);

        assertQuantities(result, new long[]{25, 25}, new long[]{10, 40});
        assertThat(result.undersubscribedQuantity()).isZero();
        assertThat(result.totalAllocatedQuantity()).isEqualTo(100);
    }

    // 균등 몫의 나머지는 균등 대상 중 추첨으로 1주씩 준다
    @Test
    void givesEqualRemainderByLottery() {
        // 균등 50 / 3 = 16 (나머지 2) → 2건이 17주
        AllocationCalculation result = calculate(100, 50, 100, 100, 100);

        assertThat(equalQuantities(result)).containsExactlyInAnyOrder(16L, 17L, 17L);
        assertThat(result.totalAllocatedQuantity()).isEqualTo(100);
    }

    // 균등 몫이 청약 수량보다 크면 청약 수량까지만 주고, 다 받은 청약은 나머지 추첨에서 빠진다 (검산 예시)
    @Test
    void capsEqualShareAtRequestedQuantity() {
        // 균등 몫 16, A는 10주만 신청 → 균등 10 / 16 / 16, 나머지 2주는 B, C가 추첨으로 1주씩 → 10 / 17 / 17
        // 비례 물량 100 - 44 = 56, 비례 기준 0 / 3 / 183 → 0 / 0.90 / 55.10 → 단주 1주는 소수점이 큰 B
        AllocationCalculation result = calculate(100, 50, 10, 20, 200);

        assertQuantities(result, new long[]{10, 17, 17}, new long[]{0, 1, 55});
        assertThat(allocatedQuantities(result)).containsExactly(10L, 18L, 72L);
        assertThat(result.undersubscribedQuantity()).isZero();
    }

    // 추첨 대상이 균등 나머지보다 적으면 남는 주식은 비례로 넘긴다
    @Test
    void movesEqualRemainderToProportionalWhenLotteryTargetsAreInsufficient() {
        // 균등 몫 16, A, B는 10주까지 → 추첨 대상은 C뿐 → C만 1주 더, 나머지 1주는 비례로
        // 균등 10 / 10 / 17, 비례 물량 100 - 37 = 63 → C가 전부
        AllocationCalculation result = calculate(100, 50, 10, 10, 200);

        assertQuantities(result, new long[]{10, 10, 17}, new long[]{0, 0, 63});
        assertThat(result.totalAllocatedQuantity()).isEqualTo(100);
    }

    // 청약이 균등 물량보다 많으면 균등 물량만큼 추첨으로 1주씩 주고, 떨어진 청약도 비례는 받는다
    @Test
    void givesEqualQuantityByLotteryWhenSubscriptionsExceedEqualQuantity() {
        // 균등 몫 0, 균등 물량 2 → 3건 중 2건이 균등 1주
        AllocationCalculation result = calculate(10, 2, 10, 10, 20);

        assertThat(equalQuantities(result)).containsExactlyInAnyOrder(0L, 1L, 1L);
        assertThat(result.allocations())
                .filteredOn(allocation -> allocation.equalAllocatedQuantity() == 0)
                .allSatisfy(allocation -> assertThat(allocation.proportionalAllocatedQuantity()).isPositive());
        assertThat(result.totalAllocatedQuantity()).isEqualTo(10);
    }

    // 청약이 균등 물량보다 많으면 정확히 균등 물량 건수만 균등 1주를 받는다
    @Test
    void picksExactlyEqualQuantityWinners() {
        long[] requested = new long[20];
        Arrays.fill(requested, 10);
        AllocationCalculation result = calculate(100, 5, requested);

        assertThat(equalQuantities(result)).filteredOn(quantity -> quantity == 1).hasSize(5);
        assertThat(equalQuantities(result)).filteredOn(quantity -> quantity == 0).hasSize(15);
    }

    // 최소 청약 수량보다 적게 신청한 청약은 균등에서 빠지고 비례만 받는다
    @Test
    void excludesBelowMinQuantityFromEqual() {
        // 균등 대상 B 1건 → 균등 0 / 50, 비례 50을 5 : 50으로 → 4.55 / 45.45 → 단주 1주는 A
        AllocationCalculation result = calculate(100, 50, 5, 100);

        assertQuantities(result, new long[]{0, 50}, new long[]{5, 45});
    }

    // 균등 대상이 없으면 균등 물량도 비례로 나눈다
    @Test
    void movesEqualQuantityToProportionalWhenNoEqualTarget() {
        // 최소 청약 수량 100, 모두 100 미만 → 비례 물량 100을 30 : 50 : 70으로 → 20 / 33.33 / 46.67 → 단주 1주는 C
        AllocationCalculation result = calculator.calculate(
                OFFER_PRICE, 100, 50, 100, LOTTERY_SEED, subscriptions(30, 50, 70));

        assertQuantities(result, new long[]{0, 0, 0}, new long[]{20, 33, 47});
        assertThat(result.undersubscribedQuantity()).isZero();
    }

    // 단주는 소수점이 큰 청약부터 1주씩 준다 (비례 배정이 더 많은 청약이 아니어도)
    @Test
    void allocatesFractionalShareByLargestRemainder() {
        // 비례 물량 10을 5 : 8로 → 3.85 / 6.15 → 단주 1주는 소수점이 큰 A
        AllocationCalculation result = calculator.calculate(OFFER_PRICE, 10, 0, 1, LOTTERY_SEED, subscriptions(5, 8));

        assertQuantities(result, new long[]{0, 0}, new long[]{4, 6});
    }

    // 소수점이 같으면 추첨으로 단주를 받을 청약을 정한다
    @Test
    void breaksFractionalShareTieByLottery() {
        // 비례 물량 9를 5 : 5로 → 4.5 / 4.5 → 단주 1주는 둘 중 하나
        Set<UUID> winners = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            AllocationCalculation result = calculator.calculate(OFFER_PRICE, 9, 0, 1, seed, subscriptions(5, 5));

            assertThat(allocatedQuantities(result)).containsExactlyInAnyOrder(4L, 5L);
            result.allocations().stream()
                    .filter(allocation -> allocation.allocatedQuantity() == 5)
                    .forEach(allocation -> winners.add(allocation.subscription().subscriptionId()));
        }
        assertThat(winners).hasSize(2);
    }

    // 청약 미달이면 모두 청약 수량만큼 받고 남는 수량은 배정하지 않는다
    @Test
    void allocatesRequestedQuantityWhenUndersubscribed() {
        AllocationCalculation result = calculate(100, 50, 30, 40);

        assertQuantities(result, new long[]{25, 25}, new long[]{5, 15});
        assertThat(result.totalAllocatedQuantity()).isEqualTo(70);
        assertThat(result.undersubscribedQuantity()).isEqualTo(30);
    }

    // 청약 수량 합이 물량과 같으면 모두 청약 수량만큼 받는다
    @Test
    void allocatesRequestedQuantityWhenTotalRequestedEqualsRetail() {
        AllocationCalculation result = calculate(100, 50, 40, 60);

        assertThat(allocatedQuantities(result)).containsExactly(40L, 60L);
        assertThat(result.undersubscribedQuantity()).isZero();
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
    }

    // 배정 대금은 배정 수량 x 공모가이고 0주도 결과에 포함한다
    @Test
    void calculatesAllocatedAmountAndIncludesZeroAllocation() {
        // A는 최소 청약 수량 미만 → 균등 0 / 1 / 1, 비례 2를 1 : 99 : 99로 → 0.01 / 0.995 / 0.995 → 단주 2주는 B, C
        AllocationCalculation result = calculate(4, 2, 1, 100, 100);

        assertThat(allocatedQuantities(result)).containsExactly(0L, 2L, 2L);
        assertThat(result.allocations())
                .allSatisfy(allocation -> assertThat(allocation.allocatedAmount())
                        .isEqualTo(allocation.allocatedQuantity() * OFFER_PRICE));
    }

    // 배정 대금이 long 범위를 넘으면 예외가 난다
    @Test
    void throwsWhenAllocatedAmountOverflows() {
        assertThatThrownBy(() -> calculator.calculate(
                Long.MAX_VALUE / 2, 100, 50, MIN_QUANTITY, LOTTERY_SEED, subscriptions(200)))
                .isInstanceOf(ArithmeticException.class);
    }

    // 비례 계산의 중간 곱이 long 범위를 넘어도 결과가 long에 들어가면 계산한다
    @Test
    void calculatesProportionalWhenIntermediateProductOverflowsLong() {
        // 비례 물량 40억 x 비례 기준 30억 = 1.2 x 10^19 > Long.MAX_VALUE. 공모가 1원은 배정 대금이 넘치지 않게
        AllocationCalculation result = calculator.calculate(
                1, 4_000_000_000L, 0, 1, LOTTERY_SEED, subscriptions(3_000_000_000L, 3_000_000_000L));

        assertQuantities(result, new long[]{0, 0}, new long[]{2_000_000_000L, 2_000_000_000L});
    }

    // 같은 시드면 같은 결과가 나온다
    @Test
    void returnsSameResultForSameSeed() {
        List<ConfirmedSubscription> subscriptions = subscriptions(10, 10, 20, 30, 15);

        AllocationCalculation first = calculator.calculate(OFFER_PRICE, 10, 3, MIN_QUANTITY, 7, subscriptions);
        AllocationCalculation second = calculator.calculate(OFFER_PRICE, 10, 3, MIN_QUANTITY, 7, subscriptions);

        assertThat(second).isEqualTo(first);
    }

    // 입력 순서가 달라도 같은 결과가 나온다 (subscriptionId 순으로 정렬)
    @Test
    void returnsSameResultRegardlessOfInputOrder() {
        List<ConfirmedSubscription> subscriptions = subscriptions(10, 10, 20, 30, 15);
        List<ConfirmedSubscription> shuffled = new ArrayList<>(subscriptions);
        Collections.shuffle(shuffled, new Random(1));

        AllocationCalculation original = calculator.calculate(OFFER_PRICE, 10, 3, MIN_QUANTITY, 7, subscriptions);
        AllocationCalculation reordered = calculator.calculate(OFFER_PRICE, 10, 3, MIN_QUANTITY, 7, shuffled);

        assertThat(reordered).isEqualTo(original);
    }

    // 시드가 다르면 추첨 결과가 달라질 수 있다
    @Test
    void changesLotteryResultWithSeed() {
        List<ConfirmedSubscription> subscriptions = subscriptions(10, 10, 10, 10, 10);
        Set<List<Long>> results = new HashSet<>();
        for (long seed = 0; seed < 20; seed++) {
            results.add(equalQuantities(calculator.calculate(OFFER_PRICE, 10, 2, MIN_QUANTITY, seed, subscriptions)));
        }

        assertThat(results).hasSizeGreaterThan(1);
    }

    // 어떤 입력이든 청약 수량을 넘지 않고, 미달이 아니면 물량을 남김없이 넘치지 않게 나눈다
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

            AllocationCalculation result = calculator.calculate(
                    OFFER_PRICE, retailQuantity, equalQuantity, MIN_QUANTITY, random.nextLong(), subscriptions(requested));

            for (SubscriptionAllocation allocation : result.allocations()) {
                assertThat(allocation.equalAllocatedQuantity()).isNotNegative();
                assertThat(allocation.proportionalAllocatedQuantity()).isNotNegative();
                assertThat(allocation.allocatedQuantity())
                        .isEqualTo(allocation.equalAllocatedQuantity() + allocation.proportionalAllocatedQuantity())
                        .isLessThanOrEqualTo(allocation.subscription().requestedQuantity());
                assertThat(allocation.allocatedAmount()).isEqualTo(allocation.allocatedQuantity() * OFFER_PRICE);
            }
            long totalRequested = Arrays.stream(requested).sum();
            assertThat(result.totalAllocatedQuantity()).isEqualTo(Math.min(retailQuantity, totalRequested));
            assertThat(result.undersubscribedQuantity()).isEqualTo(Math.max(0, retailQuantity - totalRequested));
        }
    }

    // 잘못된 입력은 거부한다
    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> calculator.calculate(0, 100, 50, MIN_QUANTITY, LOTTERY_SEED, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, 101, MIN_QUANTITY, LOTTERY_SEED, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, -1, MIN_QUANTITY, LOTTERY_SEED, subscriptions(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calculator.calculate(OFFER_PRICE, 100, 50, MIN_QUANTITY, LOTTERY_SEED, subscriptions(0)))
                .isInstanceOf(IllegalArgumentException.class);

        ConfirmedSubscription subscription = subscriptions(10).get(0);
        assertThatThrownBy(() -> calculator.calculate(
                OFFER_PRICE, 100, 50, MIN_QUANTITY, LOTTERY_SEED, List.of(subscription, subscription)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AllocationCalculation calculate(long retailQuantity, long equalQuantity, long... requested) {
        return calculator.calculate(
                OFFER_PRICE, retailQuantity, equalQuantity, MIN_QUANTITY, LOTTERY_SEED, subscriptions(requested));
    }

    // subscriptionId를 입력 순서대로 커지게 만든다 (결과는 subscriptionId 순이라 입력 순서와 같아짐)
    private static List<ConfirmedSubscription> subscriptions(long... requested) {
        List<ConfirmedSubscription> subscriptions = new ArrayList<>();
        for (int i = 0; i < requested.length; i++) {
            subscriptions.add(new ConfirmedSubscription(
                    new UUID(0, i + 1), UUID.randomUUID(), UUID.randomUUID(), requested[i]));
        }
        return subscriptions;
    }

    private static void assertQuantities(AllocationCalculation result, long[] equal, long[] proportional) {
        assertThat(equalQuantities(result)).containsExactly(boxed(equal));
        assertThat(result.allocations()).extracting(SubscriptionAllocation::proportionalAllocatedQuantity)
                .containsExactly(boxed(proportional));
    }

    private static List<Long> equalQuantities(AllocationCalculation result) {
        return result.allocations().stream().map(SubscriptionAllocation::equalAllocatedQuantity).toList();
    }

    private static List<Long> allocatedQuantities(AllocationCalculation result) {
        return result.allocations().stream().map(SubscriptionAllocation::allocatedQuantity).toList();
    }

    private static Long[] boxed(long[] values) {
        return Arrays.stream(values).boxed().toArray(Long[]::new);
    }
}
