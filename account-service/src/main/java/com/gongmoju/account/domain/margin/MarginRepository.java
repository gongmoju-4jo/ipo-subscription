package com.gongmoju.account.domain.margin;

import java.util.Optional;
import java.util.UUID;

// 구현은 infrastructure/persistence (JPA)
public interface MarginRepository {

    Margin save(Margin margin);

    // 같은 청약의 재요청 판단용 (subscription_id UNIQUE)
    Optional<Margin> findBySubscriptionId(UUID subscriptionId);

    /**
     * 쌓인 변경을 DB로 보내고(아직 커밋 전) 영속성 컨텍스트를 비운다. 한 트랜잭션에서 여러 건을 처리할 때 건마다 호출해서
     * 컨텍스트가 커지지 않게 한다. 호출 뒤에는 그전에 읽은 엔티티를 고쳐도 저장되지 않으므로 필요하면 다시 조회해야 한다.
     */
    void flushAndClear();
}