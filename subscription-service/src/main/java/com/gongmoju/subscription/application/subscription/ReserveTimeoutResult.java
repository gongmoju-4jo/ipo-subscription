package com.gongmoju.subscription.application.subscription;

/**
 * 응답 시간 초과 처리 결과 (청약 1건). 로그와 테스트 단언에 사용함.
 */
public enum ReserveTimeoutResult {
    // 최대 시도 횟수에 도달하지 않아 증거금 인출 요청을 Outbox에 다시 기록하고 요청 횟수 증가
    RETRIED,
    // 최대 시도 횟수에 도달하여 `COMPENSATING(TIMEOUT)` 전환하고 증거금 반환 요청 기록
    TIMEOUT_COMPENSATION_STARTED,
    // 상태 변경(!=PENDING)으로 아무것도 변경하지 않음
    NOT_PENDING_IGNORED,
    // 마지막 요청 시각이 갱신되어(다른 인스턴스 재요청 등) 아무것도 변경하지 않음
    NOT_TIMED_OUT_IGNORED
}
