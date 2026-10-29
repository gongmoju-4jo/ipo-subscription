package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 공모주 조회 결과. 트랜잭션 밖(컨트롤러)에서 엔티티를 다루지 않도록 값만 복사해 둔다.
 */
public record IpoResult(
        UUID ipoId,
        String name,
        long offerPrice,
        long retailQuantity,
        long equalQuantity,
        long minQuantity,
        long maxQuantity,
        BigDecimal marginRate,
        Instant subscriptionStartAt,
        Instant subscriptionEndAt,
        LocalDate refundDate,
        IpoStatus status
) {

    public static IpoResult from(Ipo ipo) {
        return new IpoResult(ipo.getId(), ipo.getName(), ipo.getOfferPrice(), ipo.getRetailQuantity(),
                ipo.getEqualQuantity(), ipo.getMinQuantity(), ipo.getMaxQuantity(), ipo.getMarginRate(),
                ipo.getSubscriptionStartAt(), ipo.getSubscriptionEndAt(), ipo.getRefundDate(), ipo.getStatus());
    }
}
