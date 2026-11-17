package com.gongmoju.account.domain.margin;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 청약증거금 선행 반환 요청 기록을 관리하는 repository
 * 구현체는 infrastructure/persistence 계층에 위치한다.
 */
public interface MarginVoidIntentRepository {

    // 해당 청약의 선행 반환 요청 기록이 있는지 확인한다.
    boolean existsBySubscriptionId(UUID subscriptionId);

    /**
     * 여러 청약 중 선행 반환 요청 기록이 있는 청약 ID만 한 번에 조회한다 (한 트랜잭션에서 여러 건을 처리할 때, 쿼리 1회).
     *
     * @param subscriptionIds 청약 ID (비어 있으면 안 됨)
     */
    Set<UUID> findSubscriptionIdsWithIntent(Collection<UUID> subscriptionIds);

    MarginVoidIntent save(MarginVoidIntent intent);
}
