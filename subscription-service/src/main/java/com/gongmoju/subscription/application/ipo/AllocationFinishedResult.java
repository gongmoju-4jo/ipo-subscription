package com.gongmoju.subscription.application.ipo;

/**
 * 배정 완료({@code allocation.finished}) 처리 결과. 로그와 테스트 단언에 사용함.
 */
public enum AllocationFinishedResult {
    // `CLOSED`를 `ALLOCATED`로 전환함
    ALLOCATED,
    // 같은 `eventId`를 이미 처리해서(`processed_event`) 아무것도 바꾸지 않음
    DUPLICATE_EVENT_IGNORED,
    // 이미 `ALLOCATED`라 중복 완료 이벤트로 보고 아무것도 바꾸지 않음
    ALREADY_ALLOCATED_IGNORED
}
