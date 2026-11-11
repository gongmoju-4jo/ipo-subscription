package com.gongmoju.gateway.queue;

/**
 * 대기열에 없음: 진입하지 않았거나, 조회가 끊겨 이탈 처리됐거나, 입장 허가가 만료됨. 화면은 다시 진입한다.
 */
class QueueEntryNotFoundException extends RuntimeException {

    QueueEntryNotFoundException() {
        super("대기열에 없습니다. 다시 진입해주세요.");
    }
}
