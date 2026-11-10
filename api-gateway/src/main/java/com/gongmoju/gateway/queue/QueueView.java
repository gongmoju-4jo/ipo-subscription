package com.gongmoju.gateway.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 진입, 순서 조회 결과를 화면에 보여줄 값으로 정리한 것.
 *
 * @param estimatedWaitSeconds 예상 대기 시간(초) = ⌈순번 ÷ 초당 입장 인원⌉. 이탈자가 빠지면 실제보다 짧아질 수 있는 어림값. 허가 상태면 0
 * @param admissionExpiresAt   허가 상태일 때 이 시각까지 청약 신청해야 한다. 대기 중이면 null
 * @param pollInterval         다음 순서 조회까지 기다릴 시간
 * @param created              이번 요청으로 새로 줄에 섰는지 (진입에서만 의미 있음)
 */
public record QueueView(
        UUID ipoId,
        QueueStatus status,
        long position,
        long waitingCount,
        long estimatedWaitSeconds,
        Instant enteredAt,
        Instant admissionExpiresAt,
        Duration pollInterval,
        boolean created
) {
}
