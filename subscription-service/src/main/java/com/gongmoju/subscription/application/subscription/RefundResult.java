package com.gongmoju.subscription.application.subscription;

/**
 * 정산 결과({@code margin.settled}) 처리 결과. 로그와 테스트 단언에 사용함.
 * {@link AllocateResult}처럼 서비스 메서드 이름({@code refund})을 따름.
 */
public enum RefundResult {
    // `ALLOCATED`를 `REFUNDED`로 전환하고 환불액을 저장함
    REFUNDED,
    // `allocation.result`보다 먼저 와서 `CONFIRMED`를 한 트랜잭션에서 `ALLOCATED`를 거쳐 `REFUNDED`로 전환함 (결정 9)
    ALLOCATED_AND_REFUNDED,
    // 같은 `eventId`를 이미 처리해서(`processed_event`) 아무것도 바꾸지 않음
    DUPLICATE_EVENT_IGNORED,
    // 이미 `REFUNDED`이고 저장한 정산 결과와 같아 중복으로 보고 아무것도 바꾸지 않음
    ALREADY_REFUNDED_IGNORED
}
