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
