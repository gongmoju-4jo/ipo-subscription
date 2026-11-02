package com.gongmoju.account.domain.event;

import java.util.UUID;

// Consumer가 처리한 이벤트 기록 (중복 수신 방지). 구현은 infrastructure/messaging (processed_event)
// 업무 변경과 같은 트랜잭션에서 호출해야 업무가 롤백되면 기록도 같이 롤백된다
public interface ProcessedEventRecorder {

    void record(UUID eventId, String consumerName, String topic);
}
