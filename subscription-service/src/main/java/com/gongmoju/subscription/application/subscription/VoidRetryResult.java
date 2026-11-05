package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;

import java.time.Instant;

/**
 * 증거금 반환 재요청 처리 결과 (청약 1건).
 * 알림 로그에 필요한 청약 정보(상태, 해제 사유, 요청 횟수, 마지막 요청 시각)를 포함. 청약이 없는 경우는 없음(예외).
 *
 * @param outcome         처리 결과
 * @param status          처리 직전 청약 상태
 * @param cancelReason    처리 직전 해제 사유
 * @param attempts        처리 직전 요청 횟수
 * @param lastRequestedAt 처리 직전 마지막 요청 시각
 */
public record VoidRetryResult(Outcome outcome, SubscriptionStatus status, CancelReason cancelReason, int attempts,
                              Instant lastRequestedAt) {

    public enum Outcome {
        // 최대 횟수에 도달하지 않아 증거금 반환 재요청
        RETRIED,
        // 최대 횟수에 도달해 아무것도 변경하지 않음 (상태 유지, 알림 대상)
        MAX_ATTEMPTS_REACHED,
        // 상태가 COMPENSATING, CANCEL_REQUESTED가 아니라서 아무것도 변경하지 않음
        NOT_VOIDING_IGNORED,
        // 마지막 요청 시각이 갱신되어 아무것도 변경하지 않음
        NOT_TIMED_OUT_IGNORED
    }

    static VoidRetryResult of(Outcome outcome, Subscription subscription) {
        return new VoidRetryResult(outcome, subscription.getStatus(), subscription.getCancelReason(),
                subscription.getAttempts(), subscription.getLastRequestedAt());
    }
}
