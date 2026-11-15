package com.gongmoju.account.domain.margin;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 구현은 infrastructure/persistence (JPA)
public interface MarginRepository {

    Margin save(Margin margin);

    // 같은 청약의 재요청 판단용 (subscription_id UNIQUE)
    Optional<Margin> findBySubscriptionId(UUID subscriptionId);

    /**
     * 여러 청약의 margin을 한 번에 조회한다 (한 트랜잭션에서 여러 건을 처리할 때, 쿼리 1회). 없는 청약은 결과에 없다.
     *
     * @param subscriptionIds 청약 ID (비어 있으면 안 됨)
     */
    List<Margin> findAllBySubscriptionIds(Collection<UUID> subscriptionIds);
}
