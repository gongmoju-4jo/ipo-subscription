package com.gongmoju.account.domain.margin;

import java.util.UUID;

/**
 * 청약증거금 선행 반환 요청 기록을 관리하는 repository
 * 구현체는 infrastructure/persistence 계층에 위치한다.
 */
public interface MarginVoidIntentRepository {

    // 해당 청약의 선행 반환 요청 기록이 있는지 확인한다.
    boolean existsBySubscriptionId(UUID subscriptionId);

    MarginVoidIntent save(MarginVoidIntent intent);
}
