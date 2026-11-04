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

    // 청약 API가 만들어 202 응답과 subscription.requested에 싣는 값이라 @GeneratedValue를 쓰지 않음
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
     * 접수 Consumer의 신규 요청 저장용 {@code PENDING} 청약 생성. {@code id}는 API가 {@code userId}와 {@code requestId}로 계산한 {@code subscriptionId}를 사용함.
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
     * 같은 {@code requestId}로 재전송된 요청이 최초 저장된 청약과 동일한 내용인지 검증함 (이벤트 명세 2-1의 재전송 내용 비교).
     * {@code eventId}, {@code occurredAt}, {@code receivedAt}은 재전송마다 달라지므로 비교 대상에서 제외함.
     *
     * @param event 재전송된 요청
     * @return 회원, 계좌, 종목, 수량이 모두 같으면 {@code true}
     */
    public boolean isSameRequest(SubscriptionRequested event) {
        return userId.equals(event.userId())
                && accountId.equals(event.accountId())
                && ipo.getId().equals(event.ipoId())
                && requestedQuantity == event.requestedQuantity();
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
     * 증거금이 인출되지 않았으므로 보상은 필요 없음. 이중청약({@code ALREADY_SUBSCRIBED})은 접수 Consumer가 저장할 때 판정하므로 이 전환의 사유가 될 수 없음.
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
     * 요청 횟수({@code attempts})가 최대 시도 횟수 이상인지 확인. {@code attempts}는 청약 접수 때 증가시키며 최대 횟수는 "총 인출 요청 횟수".
     * 최대 시도 횟수에 도달한 {@code PENDING}이 응답 시간까지 지나면 더 이상 재요청하지 않고 시간 초과 보상({@code COMPENSATING(TIMEOUT)})으로 넘어감.
     */
    public boolean hasReachedMaxAttempts(int maxAttempts) {
        return attempts >= maxAttempts;
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
