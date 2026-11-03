package com.gongmoju.allocation.domain.allocation;

import java.time.Instant;
import java.util.UUID;

/**
 * 받은 {@code ipo.closed} 내용 (이벤트 명세 2장). 형식과 범위 검증은 메시지 변환에서 끝난 값이다.
 *
 * @param eventId        이벤트 ID (중복 수신 판단)
 * @param ipoId          공모주 ID
 * @param closedAt       청약 접수 마감 시각
 * @param confirmedCount 배정 대상 확정(CONFIRMED) 청약 건수
 * @param offerPrice     공모가
 * @param retailQuantity 일반청약 물량
 * @param equalQuantity  균등배정 물량
 * @param minQuantity    최소 청약 수량
 */
public record IpoClosed(
        UUID eventId,
        UUID ipoId,
        Instant closedAt,
        long confirmedCount,
        long offerPrice,
        long retailQuantity,
        long equalQuantity,
        long minQuantity
) {
}
