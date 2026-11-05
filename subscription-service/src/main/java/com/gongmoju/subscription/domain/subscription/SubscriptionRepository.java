package com.gongmoju.subscription.domain.subscription;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /**
     * 응답 시간 초과 처리 대상 조회 (이벤트 명세 3-5, 4장 위험 4).
     * 마지막 요청 시각이 {@code lastRequestedBefore}보다 이른 {@code PENDING} 청약 ID를 오래된 순으로 최대 {@code limit}건 조회함.
     * 미발행 {@code margin.reserve-requested} Outbox 항목이 있는 청약은 응답 시간 초과 처리 대상에서 제외함
     */
    List<UUID> findStalePendingIds(Instant lastRequestedBefore, int limit);

    /**
     * 발행된 {@code margin.reserve-requested} Outbox 중 가장 늦은 발행 시각. 발행된 것이 없으면 빈 값.
     * 마지막 인출 요청이 늦게 발행됐을 때 계좌가 응답할 시간을 받기 전에 TIMEOUT 보상으로 넘어가지 않도록 확인하는 용도.
     */
    Optional<Instant> findLastReserveRequestPublishedAt(UUID subscriptionId);

    /**
     * 반환 재요청 대상 조회 (이벤트 명세 3-5, 상태 전이도의 멈춘 SAGA 정리).
     * 마지막 요청 시각이 {@code lastRequestedBefore}보다 이르고 요청 횟수가 {@code maxAttempts} 미만인 {@code COMPENSATING}, {@code CANCEL_REQUESTED} 청약 ID를
     * 오래된 순으로 최대 {@code limit}건 조회함. 미발행 {@code margin.void-requested} Outbox 항목이 있는 청약은 제외함.
     */
    List<UUID> findStaleVoidIds(Instant lastRequestedBefore, int maxAttempts, int limit);

    /**
     * 반환 재요청 최대 횟수에 이른 청약 조회 (알림 대상). {@link #findStaleVoidIds}와 같은 상태, 시각 조건에서 요청 횟수가 {@code maxAttempts} 이상인 청약 ID를
     * 오래된 순으로 최대 {@code limit}건 조회함. 재요청 대상 조회와 나눈 이유는 최대 횟수에 이른 청약은 계속 남아 재요청 대상의 건수 제한을 채우지 않게 하기 위함.
     */
    List<UUID> findExhaustedVoidIds(Instant lastRequestedBefore, int maxAttempts, int limit);

    /**
     * 반환 재요청 최대 횟수에 이른 청약의 총 건수. {@link #findExhaustedVoidIds}와 같은 조건이며 건수 제한이 없음.
     * 상세 조회는 앞쪽 {@code limit}건만 가져오므로, 대상이 그보다 많을 때 빠진 건수를 알리는 데 씀.
     */
    long countExhaustedVoid(Instant lastRequestedBefore, int maxAttempts);

    // 공모주의 청약 중 주어진 상태들의 건수 (마감 조건의 진행 중 SAGA 건수, ipo.closed의 confirmedCount, 배정 완료 때 배정 대상 청약 수)
    long countByIpoIdAndStatusIn(UUID ipoId, Collection<SubscriptionStatus> statuses);

    // 공모주의 특정 상태 청약 목록 (배정용 확정 청약 목록). 공모주 정보는 읽지 않는다
    Page<Subscription> findByIpoIdAndStatus(UUID ipoId, SubscriptionStatus status, Pageable pageable);
}
