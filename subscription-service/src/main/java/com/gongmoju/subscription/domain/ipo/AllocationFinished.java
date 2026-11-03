package com.gongmoju.subscription.domain.ipo;

import java.util.UUID;

/**
 * 배정 완료 내용. 배정 서비스가 {@code allocation.finished}로 발행한다 (필드는 이벤트 명세 {@code allocation.finished} 절).
 *
 * @param eventId                이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param totalAllocatedQuantity 공모주에서 실제 배정한 전체 주식 수. 0 이상, {@code retailQuantity} 이하
 * @param subscriptionCount      배정 결과를 만든 청약 건수 (0주 배정 포함). 정상이면 마감 때 확정 청약 수와 같음
 */
public record AllocationFinished(
        UUID eventId,
        UUID ipoId,
        long totalAllocatedQuantity,
        long subscriptionCount
) {
}
