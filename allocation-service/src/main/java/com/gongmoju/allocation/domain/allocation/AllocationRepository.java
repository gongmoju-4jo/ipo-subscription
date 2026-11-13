package com.gongmoju.allocation.domain.allocation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 배정 결과 저장소. 구현은 infrastructure/persistence (JPA)
public interface AllocationRepository {

    // 저장 후 ID가 채워진 결과를 돌려준다 (allocation.result의 aggregate_id로 씀)
    List<Allocation> saveAll(List<Allocation> allocations);

    /**
     * 지금까지 저장한 것(배정 결과, 같은 트랜잭션의 Outbox 등)을 DB로 보내고, 메모리에 들고 있던 엔티티를 놓는다.
     * 커밋은 하지 않는다 (트랜잭션이 끝날 때 함께 커밋되고, 실패하면 함께 롤백된다).
     * 호출 뒤에는 그전에 읽은 엔티티(회차 등)를 고쳐도 저장되지 않으므로 필요하면 다시 조회해야 한다.
     */
    void flushAndClear();

    // 본인 청약의 배정 결과. 남의 청약이면 없는 것과 같게 비어 있다 (API 명세 3.5)
    Optional<Allocation> findBySubscriptionIdAndUserId(UUID subscriptionId, UUID userId);

    // 회차의 배정 결과 전체 (재현용)
    List<Allocation> findAllByAllocationRound(AllocationRound allocationRound);
}
