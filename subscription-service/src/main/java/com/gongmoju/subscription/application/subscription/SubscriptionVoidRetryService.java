package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.subscription.MarginVoidRequestRecorder;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 증거금 반환 응답({@code margin.voided})이 오지 않아
 * 멈춘 {@code COMPENSATING}, {@code CANCEL_REQUESTED} 청약의 증거금 반환 재요청. 최대 횟수 없이 간격을 늘려 가며 응답이 도착할 때까지 재요청.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionVoidRetryService {

    private final SubscriptionRepository subscriptionRepository;
    private final MarginVoidRequestRecorder marginVoidRequestRecorder;

    /**
     * 증거금 반환 응답이 오지 않은 {@code COMPENSATING}, {@code CANCEL_REQUESTED} 청약의 증거금 반환을 처리(이벤트 명세 3-5, 상태 전이도의 멈춘 SAGA 정리).
     * <ul>
     *   <li>{@code COMPENSATING}, {@code CANCEL_REQUESTED}가 아님: 변경하지 않음</li>
     *   <li>마지막 요청 시각에서 대기 시간({@link VoidRetryBackoff#waitAfter})이 아직 지나지 않음: 이미 재요청되었으므로 변경하지 않음</li>
     *   <li>그 외: 증거금 반환 재요청 및 Outbox 재기록. 요청 횟수 상한은 없음 (응답이 오면 {@code margin.voided} 소비로 끝남)</li>
     * </ul>
     *
     * 반환을 오래 못 끝낸 청약의 상태는 바꾸지 않음 (자동 종료, 강제 마감 없음, 결정 14). 알림은 호출하는 쪽이 읽기 전용 조회로 따로 남김.
     * {@code TIMEOUT} 보상처럼 상태가 변경되지 않아 마지막 반환 요청의 발행 시각 확인하지 않음 (정상 청약이 취소되지 않음).
     * 낙관적 락({@code version}) 충돌은 커밋 때 예외로 올라오며, 호출하는 쪽이 건너뛰고 다음 주기에 재판단.
     *
     * @param subscriptionId 청약 ID
     * @param now            재요청 판단 기준 시각
     * @param backoff        요청 횟수별 재요청 대기 시간 계산
     * @return               재요청 처리 결과
     * @throws IllegalStateException 존재하지 않는 {@code subscriptionId} (대상 조회 결과로만 호출하므로 정상적으로 일어나지 않음)
     */
    @Transactional
    public VoidRetryResult handle(UUID subscriptionId, Instant now, VoidRetryBackoff backoff) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("반환 재요청 대상 청약이 없습니다. subscriptionId=" + subscriptionId));

        if (subscription.getStatus() != SubscriptionStatus.COMPENSATING
                && subscription.getStatus() != SubscriptionStatus.CANCEL_REQUESTED) {
            log.info("COMPENSATING, CANCEL_REQUESTED가 아니라서 반환 재요청을 건너뜀. subscriptionId={}, status={}", subscriptionId, subscription.getStatus());
            return VoidRetryResult.of(VoidRetryResult.Outcome.NOT_VOIDING_IGNORED, subscription);
        }
        Instant lastRequestedAt = subscription.getLastRequestedAt();
        Instant lastRequestedBefore = now.minus(backoff.waitAfter(subscription.getAttempts()));
        if (lastRequestedAt == null || !lastRequestedAt.isBefore(lastRequestedBefore)) {
            log.info("이미 다시 요청되어 반환 재요청을 건너뜀. subscriptionId={}, lastRequestedAt={}", subscriptionId, lastRequestedAt);
            return VoidRetryResult.of(VoidRetryResult.Outcome.NOT_TIMED_OUT_IGNORED, subscription);
        }

        VoidRetryResult result = VoidRetryResult.of(VoidRetryResult.Outcome.RETRIED, subscription);
        subscription.retryVoid();
        marginVoidRequestRecorder.record(subscription);
        log.warn("계좌 응답이 없어서 증거금 반환을 다시 요청함. subscriptionId={}, status={}, reason={}, attempts={}, 이전 마지막 요청 시각={}",
                subscriptionId, subscription.getStatus(), subscription.getCancelReason(), subscription.getAttempts(), lastRequestedAt);
        return result;
    }
}
