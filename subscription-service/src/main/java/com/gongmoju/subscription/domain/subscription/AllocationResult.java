package com.gongmoju.subscription.domain.subscription;

import java.util.UUID;

/**
 * 청약 1건의 배정 결과. 배정 서비스가 {@code allocation.result}로 발행한다 (필드는 이벤트 명세 {@code allocation.result} 절).
 * 균등, 비례 배정 수량은 메시지 변환에서 합계만 검증하고 넘기지 않는다 (청약 테이블에는 최종 배정 수량, 배정 대금만 저장, 테이블 명세).
 *
 * @param eventId           이벤트 ID. 중복 수신 방지({@code processed_event})에 사용
 * @param subscriptionId    청약 ID
 * @param ipoId             공모주 ID. 저장된 청약의 공모주와 같은지 검증에 사용
 * @param accountId         계좌 ID. 저장된 청약의 계좌와 같은지 검증에 사용
 * @param allocatedQuantity 배정 수량 (0 이상, 균등 + 비례)
 * @param allocatedAmount   배정 대금 (원, 0 이상)
 */
public record AllocationResult(
        UUID eventId,
        UUID subscriptionId,
        UUID ipoId,
        UUID accountId,
        long allocatedQuantity,
        long allocatedAmount
) {
}
