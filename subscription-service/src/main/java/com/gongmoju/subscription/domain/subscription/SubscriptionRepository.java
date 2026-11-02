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

    // 공모주의 특정 상태 청약 목록 (배정용 확정 청약 목록). 공모주 정보는 읽지 않는다
    Page<Subscription> findByIpoIdAndStatus(UUID ipoId, SubscriptionStatus status, Pageable pageable);
}
