package com.gongmoju.subscription.application.subscription;

import java.util.List;

/**
 * 배정용 확정 청약 키셋 조회 결과 (API 명세 4.1, #138).
 *
 * @param items         청약 ID 오름차순 확정 청약. 최대 요청한 페이지 크기만큼
 * @param hasNext       이 뒤에 확정 청약이 더 있는지
 * @param totalElements 공모주의 확정 청약 전체 건수. 첫 요청(afterSubscriptionId 없음)에만 있고 다음 요청은 null
 */
public record ConfirmedSubscriptionKeysetResult(
        List<ConfirmedSubscriptionResult> items,
        boolean hasNext,
        Long totalElements
) {
}
