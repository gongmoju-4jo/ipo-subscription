package com.gongmoju.gateway.queue;

/**
 * 청약 중인 공모주가 아니라 대기열에 진입할 수 없음 (없는 공모주 번호, 청약 시작 전, 마감). 게이트웨이는 청약 중(OPEN) 목록만 받아서
 * 이 셋을 구분하지 않는다. 화면은 공모주 정보로 따로 표시한다.
 */
class NotOpenIpoException extends RuntimeException {

    NotOpenIpoException() {
        super("청약 중인 공모주가 아닙니다.");
    }
}
