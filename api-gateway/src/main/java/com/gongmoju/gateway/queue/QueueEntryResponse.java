package com.gongmoju.gateway.queue;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 대기열 진입, 순서 조회 응답. 시각은 KST(+09:00)로 내려준다 (서비스 응답과 같은 규칙).
 */
public record QueueEntryResponse(
        @Schema(description = "공모주 ID", example = "8b2d0000-0000-4000-8000-000000000001")
        UUID ipoId,
        @Schema(description = "WAITING(대기 중), ADMITTED(입장 허가)", example = "WAITING")
        QueueStatus status,
        @Schema(description = "내 앞 인원 + 1 (1부터). ADMITTED면 0", example = "1234")
        long position,
        @Schema(description = "이 공모주 대기 인원", example = "5210")
        long waitingCount,
        @Schema(description = "예상 대기 시간(초). 어림값. ADMITTED면 0", example = "3")
        long estimatedWaitSeconds,
        @Schema(description = "대기열 진입 시각 (KST)", example = "2026-11-09T15:55:00.123+09:00")
        OffsetDateTime enteredAt,
        @Schema(description = "ADMITTED일 때 이 시각까지 청약 신청해야 함 (KST). WAITING이면 null",
                example = "2026-11-09T16:00:00.123+09:00", nullable = true)
        OffsetDateTime admissionExpiresAt,
        @Schema(description = "다음 순서 조회까지 기다릴 시간(ms)", example = "10420")
        long pollIntervalMillis
) {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    static QueueEntryResponse from(QueueView view) {
        return new QueueEntryResponse(
                view.ipoId(),
                view.status(),
                view.position(),
                view.waitingCount(),
                view.estimatedWaitSeconds(),
                view.enteredAt().atOffset(KST),
                view.admissionExpiresAt() == null ? null : view.admissionExpiresAt().atOffset(KST),
                view.pollInterval().toMillis());
    }
}
