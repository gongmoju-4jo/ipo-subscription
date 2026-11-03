package com.gongmoju.subscription.application.ipo;

/**
 * 공모주 마감 시도 결과. 마감하지 못했을 때 알림 로그에 진행 중 청약 건수를 남기는 데 사용함.
 *
 * @param outcome              결과
 * @param confirmedCount       마감했을 때 {@code ipo.closed}에 담은 {@code CONFIRMED} 건수. 마감하지 않았으면 0
 * @param pendingCount         {@code PENDING} 건수
 * @param compensatingCount    {@code COMPENSATING} 건수
 * @param cancelRequestedCount {@code CANCEL_REQUESTED} 건수
 */
public record IpoCloseResult(
        Outcome outcome,
        long confirmedCount,
        long pendingCount,
        long compensatingCount,
        long cancelRequestedCount
) {

    public enum Outcome {
        // OPEN → CLOSED로 바꾸고 ipo.closed를 Outbox에 기록함
        CLOSED,
        // 진행 중 청약(PENDING, COMPENSATING, CANCEL_REQUESTED)이 남아 마감하지 않음
        IN_PROGRESS_REMAINING,
        // 이미 OPEN이 아니라 아무것도 하지 않음
        NOT_OPEN
    }

    static IpoCloseResult closed(long confirmedCount) {
        return new IpoCloseResult(Outcome.CLOSED, confirmedCount, 0, 0, 0);
    }

    static IpoCloseResult inProgressRemaining(long pendingCount, long compensatingCount, long cancelRequestedCount) {
        return new IpoCloseResult(Outcome.IN_PROGRESS_REMAINING, 0, pendingCount, compensatingCount, cancelRequestedCount);
    }

    static IpoCloseResult notOpen() {
        return new IpoCloseResult(Outcome.NOT_OPEN, 0, 0, 0, 0);
    }
}
