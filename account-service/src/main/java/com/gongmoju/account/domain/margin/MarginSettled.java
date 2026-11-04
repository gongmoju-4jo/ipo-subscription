package com.gongmoju.account.domain.margin;

import java.util.Objects;
import java.util.UUID;

/**
 * 공모주 배정에 따른 증거금 정산 처리 결과.
 * <p>
 * 배정 수량이 0주인 경우에도 해당 결과로 응답한다.
 * 이 경우 postedAmount는 0, refundAmount는 증거금 전액이다.
 *
 * @param subscriptionId 관련 청약 ID
 * @param marginId       관련 증거금 ID
 * @param accountId      관련 계좌 ID
 * @param postedAmount   정산된 금액
 * @param refundAmount   환불된 금액
 */
public record MarginSettled(
        UUID subscriptionId,
        UUID marginId,
        UUID accountId,
        long postedAmount,
        long refundAmount
) {

    // 필수 ID의 null 여부와 정산, 환불 금액의 음수 여부를 검증한다.
    public MarginSettled {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(marginId, "marginId");
        Objects.requireNonNull(accountId, "accountId");
        if (postedAmount < 0 || refundAmount < 0) {
            throw new IllegalArgumentException(
                    "정산 금액은 0 이상이어야 합니다. postedAmount=" + postedAmount + ", refundAmount=" + refundAmount
            );
        }
    }
}
