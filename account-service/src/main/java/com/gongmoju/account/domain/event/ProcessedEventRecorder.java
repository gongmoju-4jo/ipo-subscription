package com.gongmoju.account.domain.event;

import java.util.UUID;

// Consumer가 처리한 이벤트 기록 (중복 수신 방지). 구현은 infrastructure/messaging (processed_event)
// 업무 변경과 같은 트랜잭션의 맨 앞에서 호출한다. 업무가 롤백되면 기록도 같이 롤백되어 재시도 때 다시 처리된다
public interface ProcessedEventRecorder {

    // 처음 받은 이벤트면 기록 저장하고 true, 이미 처리한 이벤트면 false
    boolean insertIfAbsent(UUID eventId, String consumerName, String topic);
}
