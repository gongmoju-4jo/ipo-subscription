package com.gongmoju.subscription.application.subscription;

/**
 * 보상 착수 처리 결과. 로그와 테스트 단언에 사용함.
 */
public enum CompensationResult {
    // `PENDING`을 `COMPENSATING`으로 전환하고 증거금 해제 요청을 기록함
    STARTED,
    // `PENDING`이 아니라서(이미 확정, 보상 중, 취소, 거절 등) 아무것도 바꾸지 않음
    NOT_PENDING_IGNORED
}
