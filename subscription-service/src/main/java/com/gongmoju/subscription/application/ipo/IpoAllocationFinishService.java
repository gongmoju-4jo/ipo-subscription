package com.gongmoju.subscription.application.ipo;

import com.gongmoju.subscription.domain.event.ProcessedEventRepository;
import com.gongmoju.subscription.domain.ipo.AllocationFinished;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IpoAllocationFinishService {

    // 이벤트 명세 3-2 (제안)
    public static final String CONSUMER_NAME = "subscription-allocation-finished";
    public static final String TOPIC = "allocation.finished";

    // 배정 대상이었던 청약. 마감 후에는 이 집합이 바뀌지 않고, 일부는 이미 배정 결과나 정산 결과를 받아 다음 상태로 넘어갔을 수 있음
    private static final List<SubscriptionStatus> ALLOCATION_TARGET_STATUSES =
            List.of(SubscriptionStatus.CONFIRMED, SubscriptionStatus.ALLOCATED, SubscriptionStatus.REFUNDED);

    private final ProcessedEventRepository processedEventRepository;
    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;

    /**
     * 배정 완료 이벤트를 처리함 (이벤트 명세 {@code allocation.finished} 절). 처리 기록, 공모주 상태 전환을 한 트랜잭션으로 처리함.
     * 처리 도중 예외가 나면 처리 기록도 함께 롤백되어 재시도에서 다시 처리됨.
     * <p>
     * 받은 때의 공모주 상태에 따라 처리가 다름.
     * <ul>
     *   <li>{@code CLOSED}: 배정 결과 합계를 확인한 뒤 {@code ALLOCATED}로 전환함. 합계가 맞지 않아도 에러 로그만 남기고 전환함</li>
     *   <li>{@code ALLOCATED}: 중복 완료 이벤트이므로 로그만 남기고 정상 종료함</li>
     *   <li>{@code OPEN}: 정상적으로는 오지 않는 이벤트이므로 상태를 바꾸지 않고 예외를 던짐. 배정은 {@code CLOSED} 이후의 {@code ipo.closed}로만 시작해서, 오면 배정 서비스나 데이터 문제임</li>
     * </ul>
     * 예외는 다시 처리해도 결과가 같으므로 재시도 없이 DLT로 보냄 (이벤트 명세 3-3). 처리 기록도 함께 롤백되므로,
     * 원인을 확인하고 공모주가 {@code CLOSED}가 된 뒤 DLT 메시지를 재발행하면 다시 처리됨 (이벤트 명세 3-5).
     *
     * @param event 배정 완료 내용 (필수 필드와 형식 검증을 통과한 값)
     * @return 처리 결과
     * @throws InvalidEventException 존재하지 않는 {@code ipoId}, 공모주가 {@code OPEN}인 경우
     */
    @Transactional
    public AllocationFinishedResult finish(AllocationFinished event) {
        if (!processedEventRepository.insertIfAbsent(event.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("이미 처리한 이벤트라 무시함. eventId={}, ipoId={}", event.eventId(), event.ipoId());
            return AllocationFinishedResult.DUPLICATE_EVENT_IGNORED;
        }
        Ipo ipo = ipoRepository.findById(event.ipoId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 ipoId입니다: " + event.ipoId()));

        return switch (ipo.getStatus()) {
            case CLOSED -> finishClosed(ipo, event);
            case ALLOCATED -> {
                log.info("이미 배정 완료된 공모주의 중복 완료 이벤트라 무시함. ipoId={}", ipo.getId());
                yield AllocationFinishedResult.ALREADY_ALLOCATED_IGNORED;
            }
            case OPEN -> {
                // 처리 기록을 남기고 끝내면 원인을 고친 뒤 재발행해도 중복으로 무시되므로, 예외로 롤백하고 DLT에 메시지를 남김
                log.error("정상적으로는 오지 않는 배정 완료 이벤트를 받음. 상태를 바꾸지 않고 DLT로 보냄. ipoId={}, status={}",
                        ipo.getId(), ipo.getStatus());
                throw new InvalidEventException("배정 완료 이벤트를 받을 수 없는 공모주 상태입니다: ipoId=" + ipo.getId()
                        + ", status=" + ipo.getStatus());
            }
        };
    }

    private AllocationFinishedResult finishClosed(Ipo ipo, AllocationFinished event) {
        checkTotals(ipo, event);
        ipo.finishAllocation();
        log.info("공모주 배정을 완료함. ipoId={}, totalAllocatedQuantity={}, subscriptionCount={}",
                ipo.getId(), event.totalAllocatedQuantity(), event.subscriptionCount());
        return AllocationFinishedResult.ALLOCATED;
    }

    // 배정 서비스가 보낸 합계를 청약 서비스 데이터와 맞춰 봄. 배정 결과는 이미 발행돼 되돌릴 수 없으므로 막지 않고 알리기만 함
    // 기준은 임시 (이벤트 명세 "확인 필요", 배정 담당과 합의 전)
    private void checkTotals(Ipo ipo, AllocationFinished event) {
        if (event.totalAllocatedQuantity() > ipo.getRetailQuantity()) {
            log.error("배정 전체 수량이 일반청약 물량보다 많음. 배정 결과 확인 필요. ipoId={}, totalAllocatedQuantity={}, retailQuantity={}",
                    ipo.getId(), event.totalAllocatedQuantity(), ipo.getRetailQuantity());
        }
        long targetCount = subscriptionRepository.countByIpoIdAndStatusIn(ipo.getId(), ALLOCATION_TARGET_STATUSES);
        if (event.subscriptionCount() != targetCount) {
            log.error("배정 청약 건수가 확정 청약 수와 다름. 배정에서 빠지거나 더해진 청약 확인 필요. ipoId={}, subscriptionCount={}, confirmedCount={}",
                    ipo.getId(), event.subscriptionCount(), targetCount);
        }
    }
}
