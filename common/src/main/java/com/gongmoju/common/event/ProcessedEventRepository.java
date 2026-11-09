package com.gongmoju.common.event;

import java.util.UUID;

/**
 * 처리한 이벤트 기록 저장소 (중복 수신 방지, 이벤트 명세 3-2, 3-3). 구현은 {@link ProcessedEventRepositoryAdapter}, 표는 서비스마다 자기 스키마의 {@code processed_event}.
 */
public interface ProcessedEventRepository {

    /**
     * 같은 {@code (eventId, consumerName)}가 없을 때만 처리 기록을 저장함. 업무 변경과 같은 트랜잭션 안에서 호출해야 하며,
     * 업무 변경이 롤백되면 이 기록도 함께 롤백되어 다음 재시도에서 다시 처리됨.
     * 이미 있으면 예외 없이 {@code false}를 반환하므로 같은 트랜잭션을 계속 쓸 수 있음.
     *
     * @param eventId      이벤트 ID (메시지의 {@code eventId})
     * @param consumerName Consumer 이름 (이벤트 명세 3-2)
     * @param topic        이벤트를 받은 토픽
     * @return 새로 저장했으면 {@code true}, 이미 처리한 이벤트면 {@code false}
     */
    boolean insertIfAbsent(UUID eventId, String consumerName, String topic);
}
