package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationCalculation.SubscriptionAllocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

// 균등, 비례 배정 계산
// 균등: 최소 청약 수량 이상인 청약에 equalQuantity / 균등 대상 수만큼 (청약 수량을 넘지 않게)
//       나머지는 몫을 받고도 청약 수량이 남은 균등 대상 중 추첨으로 1주씩. 청약 수량 때문에 잘린 몫과 추첨 대상이 부족해 남은 주식은 비례로
// 비례: retailQuantity - 균등 배정 합을 (청약 수량 - 균등 배정 수량)에 비례해서 나누고 1주 미만은 버림
// 단주: 버림으로 남은 주식을 소수점(정수 나머지)이 큰 청약부터 1주씩. 소수점이 같으면 추첨
// 재현: 입력을 subscriptionId 순으로 정렬하고, lotterySeed로 만든 난수 하나로 균등 추첨 → 단주 동점 순서로 뽑는다
public class AllocationCalculator {

    public AllocationCalculation calculate(long offerPrice, long retailQuantity, long equalQuantity,
                                           long minQuantity, long lotterySeed,
                                           List<ConfirmedSubscription> subscriptions) {
        validate(offerPrice, retailQuantity, equalQuantity, subscriptions);

        List<ConfirmedSubscription> sorted = subscriptions.stream()
                .sorted(Comparator.comparing(ConfirmedSubscription::subscriptionId))
                .toList();
        Random random = new Random(lotterySeed);

        long[] equalAllocated = allocateEqual(sorted, equalQuantity, minQuantity, random);
        long equalTotal = 0;
        for (long quantity : equalAllocated) {
            equalTotal += quantity;
        }

        long proportionalQuantity = retailQuantity - equalTotal;
        long[] proportionalBase = new long[sorted.size()];
        long proportionalBaseTotal = 0;
        for (int i = 0; i < sorted.size(); i++) {
            proportionalBase[i] = sorted.get(i).requestedQuantity() - equalAllocated[i];
            proportionalBaseTotal = Math.addExact(proportionalBaseTotal, proportionalBase[i]);
        }

        // 청약 미달: 모두 청약 수량만큼 받고 남는 수량은 배정하지 않음
        boolean undersubscribed = proportionalBaseTotal <= proportionalQuantity;
        long[] proportionalAllocated = undersubscribed
                ? proportionalBase
                : allocateProportional(proportionalQuantity, proportionalBase, proportionalBaseTotal, random);

        List<SubscriptionAllocation> allocations = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            long allocatedQuantity = equalAllocated[i] + proportionalAllocated[i];
            allocations.add(new SubscriptionAllocation(
                    sorted.get(i),
                    equalAllocated[i],
                    proportionalAllocated[i],
                    allocatedQuantity,
                    Math.multiplyExact(allocatedQuantity, offerPrice)));
        }

        return new AllocationCalculation(
                List.copyOf(allocations),
                undersubscribed ? proportionalQuantity - proportionalBaseTotal : 0);
    }

    private long[] allocateEqual(List<ConfirmedSubscription> sorted, long equalQuantity, long minQuantity,
                                 Random random) {
        long[] equalAllocated = new long[sorted.size()];
        List<Integer> equalTargets = IntStream.range(0, sorted.size())
                .filter(i -> sorted.get(i).requestedQuantity() >= minQuantity)
                .boxed()
                .toList();
        if (equalTargets.isEmpty()) {
            return equalAllocated;
        }

        long equalShare = equalQuantity / equalTargets.size();
        long equalRemainder = equalQuantity - equalShare * equalTargets.size();
        for (int i : equalTargets) {
            equalAllocated[i] = Math.min(equalShare, sorted.get(i).requestedQuantity());
        }

        // 나머지 추첨: 대상마다 같은 확률, 한 청약이 두 번 뽑히지 않음. 나머지는 항상 균등 대상 수보다 적다
        List<Integer> lotteryTargets = new ArrayList<>(equalTargets.stream()
                .filter(i -> sorted.get(i).requestedQuantity() > equalAllocated[i])
                .toList());
        Collections.shuffle(lotteryTargets, random);
        long winnerCount = Math.min(equalRemainder, lotteryTargets.size());
        for (int k = 0; k < winnerCount; k++) {
            equalAllocated[lotteryTargets.get(k)]++;
        }
        return equalAllocated;
    }

    private long[] allocateProportional(long proportionalQuantity, long[] proportionalBase, long proportionalBaseTotal,
                                        Random random) {
        long[] proportionalAllocated = new long[proportionalBase.length];
        long[] fractionalRemainder = new long[proportionalBase.length];
        long proportionalTotal = 0;
        for (int i = 0; i < proportionalBase.length; i++) {
            // 결과는 proportionalQuantity 이하라 long에 들어가지만 중간 곱은 long을 넘을 수 있어 BigInteger로 계산
            BigInteger[] quotientAndRemainder = BigInteger.valueOf(proportionalQuantity)
                    .multiply(BigInteger.valueOf(proportionalBase[i]))
                    .divideAndRemainder(BigInteger.valueOf(proportionalBaseTotal));
            proportionalAllocated[i] = quotientAndRemainder[0].longValueExact();
            fractionalRemainder[i] = quotientAndRemainder[1].longValueExact();
            proportionalTotal += proportionalAllocated[i];
        }

        // 단주: 섞은 뒤 소수점이 큰 순서로 안정 정렬 → 소수점이 같으면 섞인 순서(추첨)대로
        // 단주 수량은 소수점이 있는 청약 수보다 적고, 소수점이 있으면 비례 배정 < 비례 기준이라 1주를 더 받아도 청약 수량을 넘지 않는다
        long fractionalShareQuantity = proportionalQuantity - proportionalTotal;
        List<Integer> order = new ArrayList<>(IntStream.range(0, proportionalBase.length).boxed().toList());
        Collections.shuffle(order, random);
        order.sort(Comparator.comparingLong((Integer i) -> fractionalRemainder[i]).reversed());
        for (int k = 0; k < fractionalShareQuantity; k++) {
            proportionalAllocated[order.get(k)]++;
        }
        return proportionalAllocated;
    }

    private void validate(long offerPrice, long retailQuantity, long equalQuantity,
                          List<ConfirmedSubscription> subscriptions) {
        if (offerPrice <= 0) {
            throw new IllegalArgumentException("offerPrice는 0보다 커야 합니다: " + offerPrice);
        }
        if (equalQuantity < 0 || equalQuantity > retailQuantity) {
            throw new IllegalArgumentException(
                    "equalQuantity는 0 이상 retailQuantity 이하여야 합니다: " + equalQuantity + " / " + retailQuantity);
        }
        Set<UUID> subscriptionIds = new HashSet<>();
        for (ConfirmedSubscription subscription : subscriptions) {
            if (subscription.requestedQuantity() <= 0) {
                throw new IllegalArgumentException(
                        "requestedQuantity는 0보다 커야 합니다: " + subscription.subscriptionId());
            }
            if (!subscriptionIds.add(subscription.subscriptionId())) {
                throw new IllegalArgumentException("같은 청약이 두 번 있습니다: " + subscription.subscriptionId());
            }
        }
    }
}
