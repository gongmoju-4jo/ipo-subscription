package com.gongmoju.allocation.domain.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationCalculation.SubscriptionAllocation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 균등, 비례 배정 계산
// 균등: 최소 청약 수량 이상인 청약에 equalQuantity / 균등 대상 수만큼 (청약 수량을 넘지 않게)
// 비례: retailQuantity - 균등 배정 합을 (청약 수량 - 균등 배정 수량)에 비례해서, 1주 미만은 버림
// 균등 몫의 나머지와 청약 수량 때문에 잘린 균등 몫은 비례로 넘긴다
// TODO 균등 나머지 추첨, 단주(fractionalShare) 5사6입 + 추첨 재배정은 다음 단계
public class AllocationCalculator {

    public AllocationCalculation calculate(long offerPrice, long retailQuantity, long equalQuantity,
                                           long minQuantity, List<ConfirmedSubscription> subscriptions) {
        validate(offerPrice, retailQuantity, equalQuantity, subscriptions);

        long equalTargetCount = subscriptions.stream()
                .filter(subscription -> subscription.requestedQuantity() >= minQuantity)
                .count();
        long equalShare = equalTargetCount == 0 ? 0 : equalQuantity / equalTargetCount;

        long[] equalAllocated = new long[subscriptions.size()];
        long equalTotal = 0;
        for (int i = 0; i < subscriptions.size(); i++) {
            long requested = subscriptions.get(i).requestedQuantity();
            if (requested >= minQuantity) {
                equalAllocated[i] = Math.min(equalShare, requested);
                equalTotal += equalAllocated[i];
            }
        }

        long proportionalQuantity = retailQuantity - equalTotal;
        long proportionalBaseTotal = 0;
        for (int i = 0; i < subscriptions.size(); i++) {
            proportionalBaseTotal = Math.addExact(proportionalBaseTotal,
                    subscriptions.get(i).requestedQuantity() - equalAllocated[i]);
        }

        // 청약 미달: 모두 청약 수량만큼 받고 남는 수량은 배정하지 않음
        boolean undersubscribed = proportionalBaseTotal <= proportionalQuantity;

        List<SubscriptionAllocation> allocations = new ArrayList<>(subscriptions.size());
        long proportionalTotal = 0;
        for (int i = 0; i < subscriptions.size(); i++) {
            ConfirmedSubscription subscription = subscriptions.get(i);
            long proportionalBase = subscription.requestedQuantity() - equalAllocated[i];
            // 결과는 proportionalQuantity 이하라 long에 들어가지만 중간 곱은 long을 넘을 수 있어 BigInteger로 계산
            long proportionalAllocated = undersubscribed
                    ? proportionalBase
                    : BigInteger.valueOf(proportionalQuantity)
                            .multiply(BigInteger.valueOf(proportionalBase))
                            .divide(BigInteger.valueOf(proportionalBaseTotal))
                            .longValueExact();
            proportionalTotal += proportionalAllocated;

            long allocatedQuantity = equalAllocated[i] + proportionalAllocated;
            allocations.add(new SubscriptionAllocation(
                    subscription,
                    equalAllocated[i],
                    proportionalAllocated,
                    allocatedQuantity,
                    Math.multiplyExact(allocatedQuantity, offerPrice)));
        }

        long remaining = proportionalQuantity - proportionalTotal;
        return new AllocationCalculation(
                List.copyOf(allocations),
                undersubscribed ? 0 : remaining,
                undersubscribed ? remaining : 0);
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
