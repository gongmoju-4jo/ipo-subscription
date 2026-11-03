package com.gongmoju.subscription.domain.subscription;

/**
 * 증거금 해제 요청({@code margin.void-requested}) 기록. 구현은 infrastructure/messaging (Outbox).
 */
public interface MarginVoidRequestRecorder {

    /**
     * 청약 상태 전환과 같은 트랜잭션 안에서 호출해야 함. 전환이 롤백되면 기록도 함께 롤백됨.
     * 해제 사유는 청약의 {@code cancelReason}을 그대로 씀 (이벤트의 {@code reason}과 같은 값).
     *
     * @param subscription 방금 {@code COMPENSATING}으로 전환한 청약
     */
    void record(Subscription subscription);
}
