package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoClosedRecorder;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoCloseService {

    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IpoClosedRecorder ipoClosedRecorder;

    /**
     * 진행 중 청약이 없으면 공모주를 마감함 (D-16, 이벤트 명세 4장 위험 4). 상태 변경과 {@code ipo.closed} Outbox 기록을 한 트랜잭션으로 처리함.
     * <p>
     * 마감 시각 뒤 대기 시간과 접수 토픽 랙 0은 호출하는 쪽(스케줄러)이 먼저 확인함.
     * 랙 0을 먼저 확인해야 접수된 청약이 모두 DB에 저장된 상태에서 진행 중 건수를 셀 수 있음 (접수 Consumer는 DB 커밋 뒤 오프셋을 커밋함).
     * 진행 중 청약이 남아 있으면 강제로 마감하지 않음 (결정 14). 나중에 확정되는 청약이 배정에서 빠지기 때문.
     *
     * @param ipoId 공모주 ID
     * @return 마감 결과
     * @throws IllegalStateException 공모주가 없는 경우 (마감 대상 조회 결과로만 호출하므로 정상적으로는 일어나지 않음)
     */
    @Transactional
    public IpoCloseResult close(UUID ipoId) {
        Ipo ipo = ipoRepository.findById(ipoId)
                .orElseThrow(() -> new IllegalStateException("마감할 공모주가 없습니다. ipoId=" + ipoId));
        if (ipo.getStatus() != IpoStatus.OPEN) {
            return IpoCloseResult.notOpen();
        }

        long pending = count(ipoId, SubscriptionStatus.PENDING);
        long compensating = count(ipoId, SubscriptionStatus.COMPENSATING);
        long cancelRequested = count(ipoId, SubscriptionStatus.CANCEL_REQUESTED);
        if (pending + compensating + cancelRequested > 0) {
            return IpoCloseResult.inProgressRemaining(pending, compensating, cancelRequested);
        }

        long confirmed = count(ipoId, SubscriptionStatus.CONFIRMED);
        ipo.close();
        ipoClosedRecorder.record(ipo, confirmed);
        log.info("공모주를 마감함. ipoId={}, confirmedCount={}", ipoId, confirmed);
        return IpoCloseResult.closed(confirmed);
    }

    private long count(UUID ipoId, SubscriptionStatus status) {
        return subscriptionRepository.countByIpoIdAndStatusIn(ipoId, List.of(status));
    }
}
