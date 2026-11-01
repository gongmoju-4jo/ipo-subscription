package com.gongmoju.allocation.domain.allocation;

// 배정 이벤트 발행. 구현은 infrastructure/messaging (Outbox에 기록하고 발행기가 Kafka로 보낸다)
// 배정 결과 저장과 같은 트랜잭션에서 호출한다
public interface AllocationEventPublisher {

    // allocation.result: 청약 1건당 1개 (0주 포함)
    void publishResult(Allocation allocation);

    // allocation.finished: 공모주당 1개
    void publishFinished(AllocationRound allocationRound, long totalAllocatedQuantity, long subscriptionCount);
}
