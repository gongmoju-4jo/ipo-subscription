package com.gongmoju.allocation.infrastructure.client;

import java.util.List;

/**
 * 청약 서비스 내부 API 키셋 응답 (API 명세 4.1, 청약 서비스 #138). 한 번 요청해서 받은 확정 청약 묶음.
 *
 * @param items         확정 청약. 청약 서비스 DB의 subscriptionId 오름차순, 최대 요청한 size건
 * @param size          청약 서비스가 적용한 페이지 크기
 * @param hasNext       이 뒤에 확정 청약이 더 있는지. true면 마지막 항목의 subscriptionId로 다음 요청
 * @param totalElements 공모주의 확정 청약 전체 건수. 첫 요청(afterSubscriptionId 없음)에만 있고 다음 응답은 null
 */
record ConfirmedSubscriptionKeysetPage(
        List<ConfirmedSubscriptionItem> items,
        int size,
        boolean hasNext,
        Long totalElements
) {
}
