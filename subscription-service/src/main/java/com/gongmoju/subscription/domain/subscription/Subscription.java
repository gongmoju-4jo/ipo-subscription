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

    // 접수 Consumer가 새 요청을 저장할 때 쓴다 (PENDING). id는 API가 userId와 requestId로 계산한 subscriptionId
    public static Subscription accept(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                      long requestedQuantity, long marginAmount, Instant receivedAt) {
        return create(subscriptionId, requestId, ipo, userId, accountId, requestedQuantity, marginAmount, receivedAt);
    }

    // 증거금을 인출하기 전에 거절된 청약 (이중청약 등). 보상이 필요 없고 REJECTED일 때만 거절 사유가 있다
    public static Subscription reject(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                      long requestedQuantity, long marginAmount, Instant receivedAt,
                                      RejectReason rejectReason) {
        Subscription subscription = create(subscriptionId, requestId, ipo, userId, accountId,
                requestedQuantity, marginAmount, receivedAt);
        subscription.status = SubscriptionStatus.REJECTED;
        subscription.rejectReason = rejectReason;
        return subscription;
    }

    private static Subscription create(UUID subscriptionId, UUID requestId, Ipo ipo, UUID userId, UUID accountId,
                                       long requestedQuantity, long marginAmount, Instant receivedAt) {
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
