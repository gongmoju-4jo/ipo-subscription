package com.gongmoju.subscription.domain.subscription;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

// 청약 저장소. 구현은 infrastructure/persistence (JPA)
public interface SubscriptionRepository {

    // 회원의 청약 목록. 공모주(종목명)를 함께 읽는다
    Page<Subscription> findByUserId(UUID userId, Pageable pageable);

    // 회원 본인의 청약 한 건. 없거나 다른 회원의 청약이면 빈 값
    Optional<Subscription> findByIdAndUserId(UUID subscriptionId, UUID userId);

    /**
     * {@code INSERT ... ON CONFLICT (request_id) DO NOTHING} 기반 저장. 같은 {@code requestId}의 청약이 없을 때만 저장하며, 저장하면 {@code true}, 이미 있으면 {@code false}를 반환함.
     * 다른 UNIQUE 위반({@code uk_subscription_active})은 삼키지 않고 예외로 전파함.
     */
    boolean insertIfAbsent(Subscription subscription);

    /**
     * 결과 이벤트 처리용 {@code subscriptionId} 기준 청약 조회. 트랜잭션 안에서 상태를 바꾸면 커밋 때 반영됨.
     */
    Optional<Subscription> findById(UUID subscriptionId);

    /**
     * 재전송 판별용 {@code requestId} 기준 청약 조회.
     */
    Optional<Subscription> findByRequestId(UUID requestId);

    /**
     * 이중청약 판정용 조회. 같은 회원이 같은 종목에 유효한 청약({@code REJECTED}, {@code CANCELLED}가 아닌 것)을 보유했는지 확인함.
     */
    boolean existsActiveByIpoIdAndUserId(UUID ipoId, UUID userId);

    // 공모주의 청약 중 주어진 상태들의 건수 (마감 조건의 진행 중 SAGA 건수, ipo.closed의 confirmedCount, 배정 완료 때 배정 대상 청약 수)
    long countByIpoIdAndStatusIn(UUID ipoId, Collection<SubscriptionStatus> statuses);

    // 공모주의 특정 상태 청약 목록 (배정용 확정 청약 목록). 공모주 정보는 읽지 않는다
    Page<Subscription> findByIpoIdAndStatus(UUID ipoId, SubscriptionStatus status, Pageable pageable);
}
