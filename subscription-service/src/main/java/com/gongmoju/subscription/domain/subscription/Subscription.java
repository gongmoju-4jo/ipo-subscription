package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.common.entity.BaseEntity;
import com.gongmoju.subscription.domain.ipo.Ipo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

// 청약. 이중청약 방지 부분 UNIQUE(ipo_id, user_id) WHERE status NOT IN ('REJECTED','CANCELLED')는 schema.sql에 둔다 (D-14)
@Getter
@Entity
@Table(name = "subscription",
        indexes = {
                @Index(name = "ix_subscription_ipo_status", columnList = "ipo_id, status"),
                // 내 청약 목록 조회 (F-04): 회원의 청약을 최신순으로
                @Index(name = "ix_subscription_user_created", columnList = "user_id, created_at DESC")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription extends BaseEntity {

    // 청약 API가 userId와 requestId로 계산해 202 응답에 싣는 값이라 @GeneratedValue를 쓰지 않음
    @Id
    @Column(name = "subscription_id")
    private UUID id;

    @Column(nullable = false, unique = true)
    private UUID requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ipo_id", nullable = false)
    private Ipo ipo;

    // 계좌 서비스 소유라 FK 없음
    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private long requestedQuantity;

    @Column(nullable = false)
    private long marginAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionStatus status = SubscriptionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RejectReason rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CancelReason cancelReason;

    // 계좌 서비스의 증거금 ID (margin.reserved로 받음)
    private UUID marginId;

    private Long allocatedQuantity;

    private Long allocatedAmount;

    private Long refundAmount;

    @Column(nullable = false)
    private int attempts;

    private Instant lastRequestedAt;

    // 게이트웨이 도착 시각, 마감 판단 기준 (D-12)
    @Column(nullable = false)
    private Instant receivedAt;

    private Instant confirmedAt;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * 청약 API의 신규 요청 저장용 {@code PENDING} 청약 생성. {@code id}는 API가 {@code userId}와 {@code requestId}로 계산한 {@code subscriptionId}를 사용함.
     * 저장과 함께 증거금 확보 요청을 기록하므로 요청 횟수({@code attempts})는 1, 마지막 요청 시각({@code lastRequestedAt})은 현재 시각으로 설정함.
     */
    public static Subscription accept(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                      long requestedQuantity, long marginAmount, Instant receivedAt) {
        Subscription subscription = create(subscriptionId, requestId, ipo, userId, accountId,
                requestedQuantity, marginAmount, receivedAt);
        subscription.attempts = 1;
        subscription.lastRequestedAt = Instant.now();
        return subscription;
    }

    /**
     * 증거금 인출 전에 거절된 청약 생성 (이중청약 등). 보상이 필요 없으며 {@code REJECTED}일 때만 거절 사유를 저장함.
     */
    public static Subscription reject(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                      long requestedQuantity, long marginAmount, Instant receivedAt,
                                      RejectReason rejectReason) {
        Subscription subscription = create(subscriptionId, requestId, ipo, userId, accountId,
                requestedQuantity, marginAmount, receivedAt);
        subscription.status = SubscriptionStatus.REJECTED;
        subscription.rejectReason = rejectReason;
        return subscription;
    }

    /**
     * 같은 {@code requestId}로 다시 온 요청이 최초 저장된 청약과 동일한 내용인지 검증함 (재전송 내용 비교).
     * {@code receivedAt}은 재전송마다 달라지므로 비교 대상에서 제외함.
     *
     * @param userId            다시 온 요청의 회원 ID
     * @param accountId         다시 온 요청의 계좌 ID
     * @param ipoId             다시 온 요청의 종목 ID
     * @param requestedQuantity 다시 온 요청의 청약 수량
     * @return 회원, 계좌, 종목, 수량이 모두 같으면 {@code true}
     */
    public boolean isSameRequest(UUID userId, UUID accountId, UUID ipoId, long requestedQuantity) {
        return this.userId.equals(userId)
                && this.accountId.equals(accountId)
                && ipo.getId().equals(ipoId)
                && this.requestedQuantity == requestedQuantity;
    }

    /**
     * 증거금 확보 성공 응답으로 {@code PENDING}을 {@code CONFIRMED}로 전환함. 증거금 ID와 확보 시각(확정 시각)을 저장하고, 상태가 바뀌었으므로 요청 횟수({@code attempts})를 0으로 초기화함 (ERD).
     *
     * @param marginId   계좌 서비스의 증거금 ID
     * @param reservedAt 증거금 확보 시각
     * @throws IllegalStateException    {@code PENDING}이 아닌 상태에서 호출한 경우
     * @throws IllegalArgumentException {@code marginId} 또는 {@code reservedAt}이 없는 경우
     */
    public void confirm(UUID marginId, Instant reservedAt) {
        if (status != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 확정할 수 있습니다: " + status);
        }
        if (marginId == null || reservedAt == null) {
            throw new IllegalArgumentException("증거금 ID와 확보 시각은 필수입니다.");
        }
        this.status = SubscriptionStatus.CONFIRMED;
        this.marginId = marginId;
        this.confirmedAt = reservedAt;
        this.attempts = 0;
    }

    /**
     * 증거금 확보 실패 응답({@code margin.reserve-failed})으로 {@code PENDING}을 {@code REJECTED}로 전환함. 거절 사유를 저장하고, 상태가 바뀌었으므로 요청 횟수({@code attempts})를 0으로 초기화함 (ERD).
     * 증거금이 인출되지 않았으므로 보상은 필요 없음. 이중청약({@code ALREADY_SUBSCRIBED})은 청약 API가 저장할 때 판정하므로 이 전환의 사유가 될 수 없음.
     *
     * @param rejectReason 거절 사유 ({@code INSUFFICIENT_BALANCE}, {@code ACCOUNT_NOT_FOUND})
     * @throws IllegalStateException    {@code PENDING}이 아닌 상태에서 호출한 경우
     * @throws IllegalArgumentException 거절 사유가 없거나 {@code ALREADY_SUBSCRIBED}인 경우
     */
    public void rejectByMarginFailure(RejectReason rejectReason) {
        if (status != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 거절할 수 있습니다: " + status);
        }
        if (rejectReason == null || rejectReason == RejectReason.ALREADY_SUBSCRIBED) {
            throw new IllegalArgumentException("증거금 확보 실패의 거절 사유가 올바르지 않습니다: " + rejectReason);
        }
        this.status = SubscriptionStatus.REJECTED;
        this.rejectReason = rejectReason;
        this.attempts = 0;
    }

    /**
     * 증거금은 인출됐는데 청약을 확정하지 못했을 때 {@code PENDING}을 {@code COMPENSATING}으로 전환함 (보상 착수). 해제 사유를 저장함.
     * 같은 트랜잭션에서 증거금 해제 요청({@code margin.void-requested})을 기록하므로, 상태가 바뀌었어도 해제를 요청했으니 요청 횟수({@code attempts})는 1,
     * 마지막 요청 시각({@code lastRequestedAt})은 현재 시각으로 설정함 ({@link #accept}와 같은 방식, ERD).
     * 투자자 취소({@code USER_CANCEL})는 {@code CANCEL_REQUESTED}로 가는 별도 전이라서 이 전환의 사유가 될 수 없음.
     *
     * @param cancelReason 해제 사유 ({@code CONFIRM_FAILED}, {@code TIMEOUT})
     * @throws IllegalStateException    {@code PENDING}이 아닌 상태에서 호출한 경우
     * @throws IllegalArgumentException 해제 사유가 없거나 {@code USER_CANCEL}인 경우
     */
    public void startCompensation(CancelReason cancelReason) {
        if (status != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 보상을 시작할 수 있습니다: " + status);
        }
        if (cancelReason == null || cancelReason == CancelReason.USER_CANCEL) {
            throw new IllegalArgumentException("보상 착수의 해제 사유가 올바르지 않습니다: " + cancelReason);
        }
        this.status = SubscriptionStatus.COMPENSATING;
        this.cancelReason = cancelReason;
        this.attempts = 1;
        this.lastRequestedAt = Instant.now();
    }

    /**
     * 증거금 해제 완료 응답({@code margin.voided})으로 {@code COMPENSATING} 또는 {@code CANCEL_REQUESTED}를 {@code CANCELLED}로 전환함.
     * 해제 사유({@code cancelReason})는 {@code CANCELLED}에서도 필수라서 그대로 유지하고, 상태가 바뀌었고 더 요청할 것이 없으므로 요청 횟수({@code attempts})를 0으로 초기화함 (ERD).
     *
     * @throws IllegalStateException {@code COMPENSATING}, {@code CANCEL_REQUESTED}가 아닌 상태에서 호출했거나, 해제 사유가 없는 경우
     */
    public void completeCancellation() {
        if (status != SubscriptionStatus.COMPENSATING && status != SubscriptionStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("COMPENSATING, CANCEL_REQUESTED 상태에서만 취소를 완료할 수 있습니다: " + status);
        }
        if (cancelReason == null) {
            throw new IllegalStateException("해제 사유가 없는 청약은 취소를 완료할 수 없습니다: " + status);
        }
        this.status = SubscriptionStatus.CANCELLED;
        this.attempts = 0;
    }

    /**
     * 응답이 없는 {@code PENDING}의 증거금 인출 요청({@code margin.reserve-requested})을 재요청 시 요청 횟수({@code attempts})를 증가시키고 마지막 요청 시각({@code lastRequestedAt})을 현재 시각으로 갱신.
     * 상태는 바뀌지 않으므로 {@code attempts}는 초기화하지 않음 (ERD). 같은 트랜잭션에서 인출 요청을 Outbox에 다시 기록.
     *
     * @throws IllegalStateException {@code PENDING}이 아닌 상태에서 호출한 경우
     */
    public void retryReserve() {
        if (status != SubscriptionStatus.PENDING) {
            throw new IllegalStateException("PENDING 상태에서만 인출을 다시 요청할 수 있습니다: " + status);
        }
        this.attempts++;
        this.lastRequestedAt = Instant.now();
    }

    /**
     * 응답이 없는 {@code COMPENSATING}, {@code CANCEL_REQUESTED}의 증거금 반환 요청({@code margin.void-requested})을 재요청 시
     * 요청 횟수({@code attempts})를 증가시키고 마지막 요청 시각({@code lastRequestedAt})을 현재 시각으로 갱신.
     * 상태와 해제 사유({@code cancelReason})는 유지
     * 같은 트랜잭션에서 같은 해제 사유로 Outbox에 다시 기록.
     *
     * @throws IllegalStateException {@code COMPENSATING}, {@code CANCEL_REQUESTED}가 아닌 상태에서 호출했거나, 해제 사유가 없는 경우
     */
    public void retryVoid() {
        if (status != SubscriptionStatus.COMPENSATING && status != SubscriptionStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("COMPENSATING, CANCEL_REQUESTED 상태에서만 반환을 다시 요청할 수 있습니다: " + status);
        }
        if (cancelReason == null) {
            throw new IllegalStateException("해제 사유가 없는 청약은 반환을 다시 요청할 수 없습니다: " + status);
        }
        this.attempts++;
        this.lastRequestedAt = Instant.now();
    }

    /**
     * 요청 횟수({@code attempts})가 최대 시도 횟수 이상인지 확인. {@code attempts}는 청약 접수 때 증가시키며 최대 횟수는 "총 인출 요청 횟수".
     * 최대 시도 횟수에 도달한 {@code PENDING}이 응답 시간까지 지나면 더 이상 재요청하지 않고 시간 초과 보상({@code COMPENSATING(TIMEOUT)})으로 넘어감.
     */
    public boolean hasReachedMaxAttempts(int maxAttempts) {
        return attempts >= maxAttempts;
    }

    /**
     * 배정 결과({@code allocation.result})로 {@code CONFIRMED}를 {@code ALLOCATED}로 전환함. 배정 수량과 배정 대금을 저장함 (0주 배정 포함).
     * 배정 대금은 배정 수량 × 공모가와 같아야 함 (테이블 명세 {@code allocated_amount}).
     *
     * @param allocatedQuantity 배정 수량. 0 이상, 청약 수량 이하
     * @param allocatedAmount   배정 대금 (원)
     * @throws IllegalStateException    {@code CONFIRMED}가 아닌 상태에서 호출한 경우
     * @throws IllegalArgumentException 배정 수량이 범위를 벗어났거나 배정 대금이 배정 수량 × 공모가와 다른 경우
     */
    public void allocate(long allocatedQuantity, long allocatedAmount) {
        if (status != SubscriptionStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED 상태에서만 배정할 수 있습니다: " + status);
        }
        if (allocatedQuantity < 0 || allocatedQuantity > requestedQuantity) {
            throw new IllegalArgumentException("배정 수량은 0 이상 청약 수량 이하여야 합니다: allocatedQuantity="
                    + allocatedQuantity + ", requestedQuantity=" + requestedQuantity);
        }
        if (allocatedAmount != Math.multiplyExact(allocatedQuantity, ipo.getOfferPrice())) {
            throw new IllegalArgumentException("배정 대금이 배정 수량 × 공모가와 다릅니다: allocatedQuantity="
                    + allocatedQuantity + ", allocatedAmount=" + allocatedAmount + ", offerPrice=" + ipo.getOfferPrice());
        }
        this.status = SubscriptionStatus.ALLOCATED;
        this.allocatedQuantity = allocatedQuantity;
        this.allocatedAmount = allocatedAmount;
    }

    /**
     * 이미 저장한 배정 결과와 같은지 비교함. 배정 전이면 항상 {@code false}.
     *
     * @return 배정 수량과 배정 대금이 모두 같으면 {@code true}
     */
    public boolean hasSameAllocation(long allocatedQuantity, long allocatedAmount) {
        return this.allocatedQuantity != null && this.allocatedQuantity == allocatedQuantity
                && this.allocatedAmount != null && this.allocatedAmount == allocatedAmount;
    }

    /**
     * 정산 완료 응답({@code margin.settled})으로 {@code ALLOCATED}를 {@code REFUNDED}로 전환함. 환불액을 저장함 (0주 배정이면 증거금 전액, 청약 수량을 전부 배정받았으면 0).
     * 환불액은 증거금 − 배정 대금과 같아야 함 (이벤트 명세 {@code margin.settled} 절).
     *
     * @param refundAmount 환불액 (원)
     * @throws IllegalStateException    {@code ALLOCATED}가 아닌 상태에서 호출했거나, 배정 대금이 없는 경우
     * @throws IllegalArgumentException 환불액이 증거금 − 배정 대금과 다른 경우
     */
    public void completeRefund(long refundAmount) {
        if (status != SubscriptionStatus.ALLOCATED) {
            throw new IllegalStateException("ALLOCATED 상태에서만 환불을 완료할 수 있습니다: " + status);
        }
        if (allocatedAmount == null) {
            throw new IllegalStateException("배정 대금이 없는 청약은 환불을 완료할 수 없습니다: " + status);
        }
        if (refundAmount < 0 || refundAmount != marginAmount - allocatedAmount) {
            throw new IllegalArgumentException("환불액이 증거금 − 배정 대금과 다릅니다: refundAmount=" + refundAmount
                    + ", marginAmount=" + marginAmount + ", allocatedAmount=" + allocatedAmount);
        }
        this.status = SubscriptionStatus.REFUNDED;
        this.refundAmount = refundAmount;
    }

    /**
     * 이미 저장한 정산 결과와 같은지 비교함. 정산 금액은 배정 대금과 같으므로 배정 대금과 비교함. 환불 전이면 항상 {@code false}.
     *
     * @return 배정 대금과 정산 금액, 저장한 환불액과 환불액이 모두 같으면 {@code true}
     */
    public boolean hasSameRefund(long postedAmount, long refundAmount) {
        return this.allocatedAmount != null && this.allocatedAmount == postedAmount
                && this.refundAmount != null && this.refundAmount == refundAmount;
    }

    private static Subscription create(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                       long requestedQuantity, long marginAmount, Instant receivedAt) {
        if (requestedQuantity < 1) {
            throw new IllegalArgumentException("청약 수량은 1 이상이어야 합니다: " + requestedQuantity);
        }
        if (marginAmount < 1) {
            throw new IllegalArgumentException("증거금은 1 이상이어야 합니다: " + marginAmount);
        }
        Subscription subscription = new Subscription();
        subscription.id = subscriptionId;
        subscription.requestId = requestId;
        subscription.ipo = ipo;
        subscription.userId = userId;
        subscription.accountId = accountId;
        subscription.requestedQuantity = requestedQuantity;
        subscription.marginAmount = marginAmount;
        subscription.receivedAt = receivedAt;
        return subscription;
    }
}
