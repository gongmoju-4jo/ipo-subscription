package com.gongmoju.subscription.domain.ipo;

import com.gongmoju.common.entity.BaseEntity;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiErrorDetail;
import com.gongmoju.subscription.global.exception.SubscriptionErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 공모주. CHECK 제약(1 이상, 균등 물량 범위, 증거금률 범위, 기간)은 ERD_v1.1_DDL.sql 기준으로 schema.sql 또는 생성 로직에서 검증
@Getter
@Entity
@Table(name = "ipo")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Ipo extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "ipo_id")
    private UUID id;

    @Column(nullable = false, length = 30)
    private String name;

    @Column(nullable = false)
    private long offerPrice;

    @Column(nullable = false)
    private long retailQuantity;

    @Column(nullable = false)
    private long equalQuantity;

    @Column(nullable = false)
    private long minQuantity = 10;

    @Column(nullable = false)
    private long maxQuantity;

    // 증거금률 100% (D-19)
    @Column(nullable = false, precision = 3, scale = 2)
    private BigDecimal marginRate = BigDecimal.ONE;

    @Column(nullable = false)
    private Instant subscriptionStartAt;

    @Column(nullable = false)
    private Instant subscriptionEndAt;

    // 화면 표시용 (MVP는 배정 직후 자동 환불)
    @Column(nullable = false)
    private LocalDate refundDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IpoStatus status = IpoStatus.OPEN;

    @Version
    @Column(nullable = false)
    private long version;

    /**
     * 청약을 받을 수 있는지 검증한다 (API 명세 F-02 사전 검증). 상태 → 청약 기간 → 수량 순서로 확인한다.
     *
     * @param receivedAt        Gateway가 기록한 최초 도착 시각 (D-12)
     * @param requestedQuantity 청약 수량
     * @throws BusinessException OPEN이 아니면 INVALID_STATUS_TRANSITION(409),
     *                           {@code subscriptionStartAt <= receivedAt < subscriptionEndAt}가 아니면 SUBSCRIPTION_CLOSED(422),
     *                           수량이 최소 청약 수량 ~ 청약 한도 밖이면 VALIDATION_ERROR(400)
     */
    public void validateSubscription(Instant receivedAt, long requestedQuantity) {
        if (status != IpoStatus.OPEN) {
            throw new BusinessException(SubscriptionErrorCode.INVALID_STATUS_TRANSITION,
                    SubscriptionErrorCode.INVALID_STATUS_TRANSITION.getMessage(),
                    List.of(new ApiErrorDetail("ipoId", "청약 중인 공모주가 아닙니다.")));
        }
        if (receivedAt.isBefore(subscriptionStartAt) || !receivedAt.isBefore(subscriptionEndAt)) {
            throw new BusinessException(SubscriptionErrorCode.SUBSCRIPTION_CLOSED,
                    SubscriptionErrorCode.SUBSCRIPTION_CLOSED.getMessage(),
                    List.of(new ApiErrorDetail("ipoId", "청약 시작 전이거나 마감 시각이 지났습니다.")));
        }
        if (requestedQuantity < minQuantity || requestedQuantity > maxQuantity) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    CommonErrorCode.VALIDATION_ERROR.getMessage(),
                    List.of(new ApiErrorDetail("requestedQuantity",
                            "청약 수량은 " + minQuantity + " 이상 " + maxQuantity + " 이하여야 합니다.")));
        }
    }
}
