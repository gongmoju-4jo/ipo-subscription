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
        IpoStatus status,
        // 경쟁률 (F-15). 목록, 상세 조회 응답에만 내보내고, 등록과 내부 API에서는 계산하지 않아 null
        BigDecimal competitionRate,
        // 등록 시각. 등록 응답(API 명세 3.6)에만 내보내고 조회 응답에는 넣지 않는다
        Instant createdAt
) {

    // 경쟁률 없이 (등록, 내부 API)
    public static IpoResult from(Ipo ipo) {
        return of(ipo, null);
    }

    // 경쟁률과 함께 (목록, 상세 조회)
    public static IpoResult of(Ipo ipo, BigDecimal competitionRate) {
        return new IpoResult(ipo.getId(), ipo.getName(), ipo.getOfferPrice(), ipo.getRetailQuantity(),
                ipo.getEqualQuantity(), ipo.getMinQuantity(), ipo.getMaxQuantity(), ipo.getMarginRate(),
                ipo.getSubscriptionStartAt(), ipo.getSubscriptionEndAt(), ipo.getRefundDate(), ipo.getStatus(),
                competitionRate, ipo.getCreatedAt());
    }
}
