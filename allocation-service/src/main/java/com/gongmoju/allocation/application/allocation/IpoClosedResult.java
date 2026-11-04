package com.gongmoju.allocation.application.allocation;

// ipo.closed 처리 결과 (Consumer 로그용)
public enum IpoClosedResult {
    // 배정을 실행하고 회차를 DONE으로 바꿨다
    ALLOCATED,
    // 이 공모주의 배정이 이미 끝났다 (eventId가 다른 ipo.closed가 다시 와도 배정은 한 번만)
    ALREADY_DONE_IGNORED,
    // 같은 eventId를 이미 처리했다
    DUPLICATE_EVENT_IGNORED
}
