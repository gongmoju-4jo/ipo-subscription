package com.gongmoju.subscription.domain.subscription;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

// 청약 저장소. 구현은 infrastructure/persistence (JPA)
public interface SubscriptionRepository {

    // 회원의 청약 목록. 공모주(종목명)를 함께 읽는다
    Page<Subscription> findByUserId(UUID userId, Pageable pageable);

    // 회원 본인의 청약 한 건. 없거나 다른 회원의 청약이면 빈 값
    Optional<Subscription> findByIdAndUserId(UUID subscriptionId, UUID userId);

    // 같은 requestId의 청약이 없을 때만 저장한다 (INSERT ... ON CONFLICT (request_id) DO NOTHING). 저장했으면 true, 이미 있으면 false
    // 다른 UNIQUE 위반(uk_subscription_active)은 삼키지 않고 예외로 올린다
    boolean insertIfAbsent(Subscription subscription);

    // 재전송 판별용. 같은 requestId의 청약
    Optional<Subscription> findByRequestId(UUID requestId);

    // 같은 회원이 같은 종목에 유효한 청약(REJECTED, CANCELLED가 아닌 것)이 있는지. 이중청약 판정용
    boolean existsActiveByIpoIdAndUserId(UUID ipoId, UUID userId);
}
