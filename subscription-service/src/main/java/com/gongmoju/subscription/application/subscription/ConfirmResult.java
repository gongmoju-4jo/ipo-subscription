package com.gongmoju.subscription.application.subscription;

/**
 * 증거금 확보 성공 응답({@code margin.reserved}) 처리 결과. 로그와 테스트 단언에 사용함.
 */
public enum ConfirmResult {
    // `PENDING`을 `CONFIRMED`로 전환함
    CONFIRMED,
    // 같은 `eventId`를 이미 처리해서(`processed_event`) 아무것도 바꾸지 않음
    DUPLICATE_EVENT_IGNORED,
    // 이미 `CONFIRMED` 이후 상태라 중복 응답으로 보고 아무것도 바꾸지 않음
    ALREADY_CONFIRMED_IGNORED,
    // `COMPENSATING`, `CANCELLED`, `REJECTED`에서 받음. 정상적으로는 오지 않는 응답이라 에러 로그만 남기고 상태는 바꾸지 않음
    UNEXPECTED_STATE_IGNORED
}
