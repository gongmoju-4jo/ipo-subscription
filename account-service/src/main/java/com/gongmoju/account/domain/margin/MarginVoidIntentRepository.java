package com.gongmoju.account.domain.margin;

import java.util.UUID;

// 구현은 infrastructure/persistence (JPA)
public interface MarginVoidIntentRepository {

    // 인출 전에 선행 반환 기록이 있는지 확인
    boolean existsBySubscriptionId(UUID subscriptionId);
}
