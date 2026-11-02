package com.gongmoju.subscription.application.subscription;

/**
 * 증거금 확보 실패 응답({@code margin.reserve-failed}) 처리 결과. 로그와 테스트 단언에 사용함.
 */
public enum ReserveFailedResult {
    // `PENDING`을 `REJECTED`로 전환하고 거절 사유를 저장함
    REJECTED,
    // 같은 `eventId`를 이미 처리해서(`processed_event`) 아무것도 바꾸지 않음
    DUPLICATE_EVENT_IGNORED,
    // 이미 `REJECTED`라 중복 응답으로 보고 아무것도 바꾸지 않음
    ALREADY_REJECTED_IGNORED,
    // `PENDING`, `REJECTED` 외 상태에서 받음. 정상적으로는 오지 않는 응답이라 에러 로그만 남기고 상태는 바꾸지 않음
    UNEXPECTED_STATE_IGNORED
}
