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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 공모주. CHECK 제약(1 이상, 균등 물량 범위, 증거금률 범위, 기간)은 schema.sql에 있고, 등록(register)에서도 같은 규칙을 확인한다
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

    // 환불일은 마감 시각의 KST 날짜와 비교한다 (schema.sql ck_ipo_refund_date와 같은 기준)
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 관리자가 공모주를 등록한다 (API 명세 3.6, F-18). 상태는 OPEN으로 시작한다.
     * 값의 범위와 필드끼리의 관계를 모두 확인하고, 어긴 규칙을 한꺼번에 details로 모아 던진다.
     * 규칙은 schema.sql의 ck_ipo_* 제약과 같고, 증거금률과 마감 시각은 더 좁게 받는다.
     * 종목명(필수, 30자)은 요청 형식이라 요청 DTO가 확인한다.
     * <p>
     * 검사를 통과한 값이 저장(DB 제약)이나 이후 계산에서 실패하지 않게 맞춘다.
     * <ul>
     *   <li>금액 계산(청약 금액 = 수량 x 공모가, 배정 대금 합계 = 물량 x 공모가)과 DB 제약의 균등 물량 x 2가 long 범위를 넘지 않게 상한을 둔다</li>
     *   <li>시각은 DB(TIMESTAMPTZ)와 같은 마이크로초로 잘라서 검사, 저장한다. 나노초를 DB가 반올림하면 검사를 통과한 값이 제약에 걸릴 수 있다</li>
     *   <li>증거금률은 1만 받으므로 컬럼 자릿수에 맞춰 1.00으로 저장한다 (등록 응답과 조회 응답이 같게)</li>
     * </ul>
     *
     * @param minQuantity 최소 청약 수량 (요청에 없으면 호출하는 쪽에서 기본값 10을 넣는다)
     * @param marginRate  증거금률. 1(100%)만 받는다. 청약 접수가 100%만 처리하기 때문 (D-18)
     * @param now         지금 시각. 마감 시각이 이미 지난 공모주는 등록하지 않는다 (시작 시각이 지난 것은 허용)
     * @throws BusinessException 규칙을 어기면 VALIDATION_ERROR(400), details에 필드별 사유
     */
    public static Ipo register(String name, long offerPrice, long retailQuantity, long equalQuantity,
                               long minQuantity, long maxQuantity, BigDecimal marginRate,
                               Instant subscriptionStartAt, Instant subscriptionEndAt, LocalDate refundDate,
                               Instant now) {
        subscriptionStartAt = subscriptionStartAt.truncatedTo(ChronoUnit.MICROS);
        subscriptionEndAt = subscriptionEndAt.truncatedTo(ChronoUnit.MICROS);

        List<ApiErrorDetail> violations = new ArrayList<>();
        if (offerPrice < 1) {
            violations.add(new ApiErrorDetail("offerPrice", "1 이상이어야 합니다."));
        }
        if (retailQuantity < 1) {
            violations.add(new ApiErrorDetail("retailQuantity", "1 이상이어야 합니다."));
        } else if (retailQuantity > Long.MAX_VALUE / 2 || (offerPrice >= 1 && retailQuantity > Long.MAX_VALUE / offerPrice)) {
            violations.add(new ApiErrorDetail("retailQuantity", "공모가와 일반청약 물량이 너무 커서 금액을 계산할 수 없습니다."));
        }
        // 균등 물량 x 2 ≥ 일반청약 물량. 일반청약 물량이 범위를 넘은 경우에도 넘치지 않게 빼서 비교한다
        if (equalQuantity < retailQuantity - equalQuantity || equalQuantity > retailQuantity) {
            violations.add(new ApiErrorDetail("equalQuantity", "일반청약 물량의 절반 이상, 일반청약 물량 이하여야 합니다."));
        }
        if (minQuantity < 1) {
            violations.add(new ApiErrorDetail("minQuantity", "1 이상이어야 합니다."));
        }
        if (maxQuantity < minQuantity) {
            violations.add(new ApiErrorDetail("maxQuantity", "최소 청약 수량 이상이어야 합니다."));
        } else if (offerPrice >= 1 && maxQuantity > Long.MAX_VALUE / offerPrice) {
            violations.add(new ApiErrorDetail("maxQuantity", "공모가와 청약 한도가 너무 커서 청약 금액을 계산할 수 없습니다."));
        }
        if (marginRate.compareTo(BigDecimal.ONE) != 0) {
            violations.add(new ApiErrorDetail("marginRate", "증거금률은 1.00(100%)만 등록할 수 있습니다."));
        }
        if (!subscriptionEndAt.isAfter(subscriptionStartAt)) {
            violations.add(new ApiErrorDetail("subscriptionEndAt", "청약 시작 시각보다 뒤여야 합니다."));
        }
        if (!subscriptionEndAt.isAfter(now)) {
            violations.add(new ApiErrorDetail("subscriptionEndAt", "현재 시각보다 뒤여야 합니다."));
        }
        if (!refundDate.isAfter(LocalDate.ofInstant(subscriptionEndAt, KST))) {
            violations.add(new ApiErrorDetail("refundDate", "청약 마감일(KST)보다 뒤여야 합니다."));
        }
        if (!violations.isEmpty()) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    CommonErrorCode.VALIDATION_ERROR.getMessage(), List.copyOf(violations));
        }

        Ipo ipo = new Ipo();
        ipo.name = name;
        ipo.offerPrice = offerPrice;
        ipo.retailQuantity = retailQuantity;
        ipo.equalQuantity = equalQuantity;
        ipo.minQuantity = minQuantity;
        ipo.maxQuantity = maxQuantity;
        ipo.marginRate = marginRate.setScale(2);
        ipo.subscriptionStartAt = subscriptionStartAt;
        ipo.subscriptionEndAt = subscriptionEndAt;
        ipo.refundDate = refundDate;
        return ipo;
    }

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

    /**
     * 증거금률 100%만 허용함 (D-18). 다른 값은 증거금 계산 규칙(반올림)이 미정이므로 접수하지 않음. 스케일이 달라도({@code 1}, {@code 1.00}) 같은 값으로 판단함.
     */
    public boolean hasFullMarginRate() {
        return marginRate.compareTo(BigDecimal.ONE) == 0;
    }

    /**
     * 청약을 마감한다 (OPEN → CLOSED, D-16). 마감 조건(대기 시간, 진행 중 SAGA 0건)은 호출하는 쪽에서 확인한다.
     *
     * @throws BusinessException OPEN이 아니면 INVALID_STATUS_TRANSITION(409)
     */
    public void close() {
        if (status != IpoStatus.OPEN) {
            throw new BusinessException(SubscriptionErrorCode.INVALID_STATUS_TRANSITION,
                    SubscriptionErrorCode.INVALID_STATUS_TRANSITION.getMessage(),
                    List.of(new ApiErrorDetail("ipoId", "청약 중인 공모주가 아닙니다.")));
        }
        status = IpoStatus.CLOSED;
    }

    /**
     * 배정용 확정 청약 목록을 줄 수 있는지 검증한다 (API 명세 4.1). 마감(CLOSED) 이후에는 확정 청약이 더 생기거나 바뀌지 않는다.
     *
     * @throws BusinessException CLOSED가 아니면 INVALID_STATUS_TRANSITION(409)
     */
    public void validateClosed() {
        if (status != IpoStatus.CLOSED) {
            throw new BusinessException(SubscriptionErrorCode.INVALID_STATUS_TRANSITION,
                    SubscriptionErrorCode.INVALID_STATUS_TRANSITION.getMessage(),
                    List.of(new ApiErrorDetail("ipoId", "마감된 공모주가 아닙니다.")));
        }
    }

    /**
     * 배정을 완료한다 (CLOSED → ALLOCATED, 이벤트 명세 {@code allocation.finished}). 개별 청약의 정산, 환불 완료까지 뜻하지는 않는다.
     *
     * @throws BusinessException CLOSED가 아니면 INVALID_STATUS_TRANSITION(409)
     */
    public void finishAllocation() {
        validateClosed();
        status = IpoStatus.ALLOCATED;
    }
}
