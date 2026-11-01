package com.gongmoju.subscription.application.subscription;

// 접수 Consumer의 처리 결과. 로그와 테스트 단언에 사용함
public enum AcceptResult {
    // `PENDING`으로 저장하고 증거금 확보 요청을 기록함
    ACCEPTED,
    // 같은 회원이 같은 종목에 유효한 청약이 이미 있어 `REJECTED(ALREADY_SUBSCRIBED)`로 저장함
    REJECTED_ALREADY_SUBSCRIBED,
    // 같은 `requestId`로 같은 내용이 다시 온 재전송이라 아무것도 저장하지 않음
    DUPLICATE_IGNORED
}
