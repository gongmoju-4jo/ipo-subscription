package com.gongmoju.gateway.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * 진입, 순번 조회 결과.
 *
 * @param status       대기 중이거나 입장 허가
 * @param position     내 앞 인원 + 1 (1부터). 허가 상태면 0
 * @param waitingCount 이 공모주의 대기 인원
 * @param enteredAt    대기열 진입 시각 (다시 진입해도 처음 시각)
 * @param admissionTtl 허가 남은 시간. 대기 중이면 null
 * @param created      이번 요청으로 새로 진입했는지 (진입에서만 의미 있음)
 */
public record QueueState(
        QueueStatus status,
        long position,
        long waitingCount,
        Instant enteredAt,
        Duration admissionTtl,
        boolean created
) {
}
