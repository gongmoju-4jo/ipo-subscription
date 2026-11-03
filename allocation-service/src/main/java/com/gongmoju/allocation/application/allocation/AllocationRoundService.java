package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationRound;
import com.gongmoju.allocation.domain.allocation.AllocationRoundRepository;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import com.gongmoju.allocation.domain.event.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 배정 회차의 상태 변경을 단계마다 별도 트랜잭션으로 처리한다.
 * 확정 청약 목록 조회(Feign)를 트랜잭션 밖에서 하려고 {@link IpoClosedService}와 나눴다 (같은 클래스 안에서 호출하면 트랜잭션이 적용되지 않음).
 * 같은 공모주의 ipo.closed는 키가 ipoId라 한 파티션에서 순서대로 처리되므로 회차를 동시에 바꾸는 경우는 따로 막지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AllocationRoundService {

    public static final String CONSUMER_NAME = "allocation-ipo-closed";
    public static final String TOPIC = "ipo.closed";

    private final AllocationRoundRepository allocationRoundRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final AllocationService allocationService;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 배정 회차를 만들거나 다시 실행할 상태로 바꾼다.
     * 처음이면 받은 조건과 새 추첨 시드로 RUNNING 회차를 만들고, FAILED나 RUNNING(처리 중 멈춤)이면 저장한 조건과 시드 그대로 RUNNING으로 되돌린다.
     *
     * @param event 받은 ipo.closed
     * @return 배정할 회차 ID. 이미 배정이 끝난(DONE) 공모주면 빈 값
     */
    @Transactional
    public Optional<UUID> start(IpoClosed event) {
        Optional<AllocationRound> found = allocationRoundRepository.findByIpoId(event.ipoId());
        if (found.isEmpty()) {
            AllocationRound round = allocationRoundRepository.save(AllocationRound.start(event.ipoId(), event.offerPrice(),
                    event.retailQuantity(), event.equalQuantity(), event.minQuantity(), secureRandom.nextLong(), Instant.now()));
            return Optional.of(round.getId());
        }

        AllocationRound round = found.get();
        switch (round.getStatus()) {
            case DONE -> {
                return Optional.empty();
            }
            case FAILED, RUNNING -> {
                if (!round.hasSameConditions(event.offerPrice(), event.retailQuantity(), event.equalQuantity(), event.minQuantity())) {
                    // 같은 공모주의 조건은 바뀌지 않아야 한다. 결과를 재현할 수 있도록 처음 저장한 조건으로 배정하고 원인은 확인한다
                    log.error("다시 받은 ipo.closed의 공모주 조건이 저장한 회차와 다릅니다. 저장한 조건으로 배정합니다. ipoId={}, eventId={}, allocationRoundId={}",
                            event.ipoId(), event.eventId(), round.getId());
                }
                round.restart();
                return Optional.of(round.getId());
            }
            default -> throw new IllegalStateException("알 수 없는 배정 회차 상태입니다. status=" + round.getStatus());
        }
    }

    /**
     * 처리 기록과 배정 실행(배정 결과, Outbox, 회차 DONE)을 한 트랜잭션으로 처리한다. 배정 중 실패하면 처리 기록도 함께 롤백된다.
     *
     * @param allocationRoundId 배정 회차 ID (RUNNING)
     * @param subscriptions     확정 청약 전체
     * @param event             받은 ipo.closed
     * @return 배정했으면 {@code ALLOCATED}, 같은 eventId를 이미 처리했으면 {@code DUPLICATE_EVENT_IGNORED}
     */
    @Transactional
    public IpoClosedResult complete(UUID allocationRoundId, List<ConfirmedSubscription> subscriptions, IpoClosed event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            return IpoClosedResult.DUPLICATE_EVENT_IGNORED;
        }
        allocationService.allocate(allocationRoundId, subscriptions);
        return IpoClosedResult.ALLOCATED;
    }

    // 배정을 끝내지 못한 회차를 FAILED로 기록한다
    @Transactional
    public void fail(UUID allocationRoundId) {
        AllocationRound round = allocationRoundRepository.findById(allocationRoundId)
                .orElseThrow(() -> new IllegalStateException("배정 회차가 없습니다. allocationRoundId=" + allocationRoundId));
        round.fail(Instant.now());
    }
}
