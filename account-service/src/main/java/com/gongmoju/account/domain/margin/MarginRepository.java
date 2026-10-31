package com.gongmoju.account.domain.margin;

import java.util.Optional;
import java.util.UUID;

// 구현은 infrastructure/persistence (JPA)
public interface MarginRepository {

    Margin save(Margin margin);

    // 같은 청약의 재요청 판단용 (subscription_id UNIQUE)
    Optional<Margin> findBySubscriptionId(UUID subscriptionId);
}