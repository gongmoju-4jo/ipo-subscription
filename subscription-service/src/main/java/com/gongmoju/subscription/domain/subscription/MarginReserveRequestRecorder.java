package com.gongmoju.subscription.domain.subscription;

/**
 * 증거금 확보 요청({@code margin.reserve-requested}) 기록. 구현은 infrastructure/messaging (Outbox).
 */
public interface MarginReserveRequestRecorder {

    /**
     * 청약 저장과 같은 트랜잭션 안에서 호출해야 함. 청약 저장이 롤백되면 기록도 함께 롤백됨.
     *
     * @param subscription 방금 {@code PENDING}으로 저장한 청약
     */
    void record(Subscription subscription);
}
