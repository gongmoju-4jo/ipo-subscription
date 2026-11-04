package com.gongmoju.subscription.application.subscription;

/**
 * 배정 결과({@code allocation.result}) 처리 결과. 로그와 테스트 단언에 사용함.
 * 받은 이벤트 내용은 토픽 이름을 따라 {@code AllocationResult}인데, 처리 결과까지 토픽 이름을 따르면 {@code AllocationResultResult}가 되어 어색하므로
 * 서비스 메서드 이름({@code allocate})을 따름.
 */
public enum AllocateResult {
    // `CONFIRMED`를 `ALLOCATED`로 전환하고 배정 수량, 배정 대금을 저장함
    ALLOCATED,
    // 같은 `eventId`를 이미 처리해서(`processed_event`) 아무것도 바꾸지 않음
    DUPLICATE_EVENT_IGNORED,
    // 이미 `ALLOCATED` 또는 `REFUNDED`이고 저장한 배정 결과와 같아 중복으로 보고 아무것도 바꾸지 않음
    ALREADY_ALLOCATED_IGNORED
}
