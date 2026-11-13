package com.gongmoju.subscription.application.ipo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 관리자 공모주 등록 명령 (API 명세 3.6). 선택값(최소 청약 수량, 증거금률)은 기본값이 채워진 상태로 받는다.
 */
public record IpoRegisterCommand(
        String name,
        long offerPrice,
        long retailQuantity,
        long equalQuantity,
        long minQuantity,
        long maxQuantity,
        BigDecimal marginRate,
        Instant subscriptionStartAt,
        Instant subscriptionEndAt,
        LocalDate refundDate
) {
}
