package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.MarginReserveRequestRecorder;
import com.gongmoju.subscription.domain.subscription.RejectReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionAcceptService {

    // PENDING 저장 시도 횟수. 충돌한 유효 청약이 확인 전에 끝나 충돌 상대가 사라진 경우 한 번 더 저장함
    private static final int MAX_SAVE_ATTEMPTS = 2;

    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MarginReserveRequestRecorder marginReserveRequestRecorder;

    /**
     * 청약 API의 접수를 한 트랜잭션으로 처리함 (D-29 B-2). 검증, 이중청약 판정, 청약 저장, 증거금 인출 요청 Outbox 기록까지 하고 커밋하면 접수가 끝남.
     * <p>
     * 상태, 기간, 수량 검증을 통과한 요청만 공모주 행을 공유 잠금(FOR SHARE)으로 다시 읽고, 잠근 뒤 최신 상태로 한 번 더 검증함.
     * 거절될 요청(마감 시각 뒤 도착 등)은 행을 잡지 않으므로, 마감(FOR UPDATE)은 마감 전에 도착해 처리 중인 접수만 기다림.
     * 마감이 먼저 행을 잡으면 마감이 끝난 뒤 {@code CLOSED}를 읽고 거절되고, 접수가 먼저 잡으면 마감이 이 청약까지 세도록 커밋을 기다림.
     * 같은 {@code requestId}의 청약은 정상 요청마다 먼저 조회하지 않음. 접수할 수 있는 요청이면 저장할 때 {@code request_id} 충돌로 알게 되고,
     * 검증에서 거절될 요청이면 그때 조회함. 어느 쪽이든 같은 내용이면 마감 뒤에 다시 와도 처음과 같이 접수된 것으로 응답함.
     *
     * @param subscriptionId 회원 ID와 requestId로 계산한 청약 ID ({@link SubscriptionIdGenerator}). 응답과 저장에 같은 값을 쓰도록 호출하는 쪽이 한 번 계산해 넘김
     * @param userId         Gateway가 넘긴 회원 ID
     * @param receivedAt     Gateway가 기록한 최초 도착 시각 (D-12)
     * @param command        청약 신청 내용
     * @return 접수 결과. 새로 접수했거나, 이중청약으로 거절해 저장했거나, 같은 내용의 재요청이라 아무것도 저장하지 않음
     * @throws BusinessException     공모주가 없으면 RESOURCE_NOT_FOUND(404), 같은 {@code requestId}로 내용이 다르거나 다른 회원이 쓴 요청이면
     *                               DUPLICATE_RESOURCE(409), 청약 조건 위반은 {@link Ipo#validateSubscription}
     * @throws IllegalStateException 증거금률이 100%가 아니거나 증거금이 {@code long} 범위를 넘는 공모주 (공모주 데이터 오류, 500)
     */
    @Transactional
    public AcceptResult accept(UUID subscriptionId, UUID userId, Instant receivedAt, SubscribeCommand command) {
        Ipo ipo = ipoRepository.findById(command.ipoId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "공모주를 찾을 수 없습니다.", "ipoId"));

        long marginAmount;
        try {
            marginAmount = validateAndLock(ipo, receivedAt, command);
        } catch (BusinessException | IllegalStateException e) {
            // 접수할 수 없는 요청이어도 같은 requestId의 청약이 이미 있으면 처음 요청의 결과로 응답한다 (마감 뒤 재전송 등).
            // 정상 요청마다 먼저 조회하지 않고, 거절될 때만 조회한다. 접수할 수 있는 재전송은 저장할 때 request_id 충돌로 알게 된다
            Optional<Subscription> existing = subscriptionRepository.findByRequestId(command.requestId());
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), userId, command);
            }
            throw e;
        }

        // 재전송, 이중청약을 먼저 조회하지 않고 저장해 본다. 저장하면 정상 접수이고, 0행이면 그때 무엇과 충돌했는지 가린다
        Subscription subscription = Subscription.accept(subscriptionId, command.requestId(), ipo, userId, command.accountId(),
                command.requestedQuantity(), marginAmount, receivedAt);
        return savePending(subscription, userId, command);
    }

    /**
     * {@code PENDING}을 저장하고, 0행이면 {@link #resolveConflict}로 충돌을 가림.
     * 충돌한 유효 청약이 저장과 확인 사이에 끝나(거절, 취소) 충돌 상대가 사라졌으면 한 번 더 저장해 봄. 두 번째도 가릴 수 없으면 모르는 충돌로 예외.
     */
    private AcceptResult savePending(Subscription pending, UUID userId, SubscribeCommand command) {
        for (int attempt = 1; attempt <= MAX_SAVE_ATTEMPTS; attempt++) {
            if (subscriptionRepository.insertIfAbsent(pending)) {
                marginReserveRequestRecorder.record(pending);
                return AcceptResult.ACCEPTED;
            }
            Optional<AcceptResult> resolved = resolveConflict(pending, userId, command);
            if (resolved.isPresent()) {
                return resolved.get();
            }
            log.info("저장이 충돌했지만 충돌한 유효 청약이 그사이 끝나 다시 저장함. requestId={}, userId={}, ipoId={}, attempt={}",
                    command.requestId(), userId, command.ipoId(), attempt);
        }
        throw new IllegalStateException("청약 저장이 같은 requestId, 이중청약이 아닌 이유로 충돌했습니다. requestId=" + command.requestId());
    }

    /**
     * 잠그기 전에 거절될 요청을 거른 뒤, 통과한 요청만 공모주 행을 공유 잠금(FOR SHARE)으로 다시 읽고 최신 상태로 한 번 더 검증함.
     * 그사이 마감됐을 수 있어서 잠근 뒤에도 검증함.
     *
     * @return 증거금 (청약 수량 x 공모가, 증거금률 100%)
     * @throws BusinessException     청약 조건 위반 ({@link Ipo#validateSubscription})
     * @throws IllegalStateException 증거금률이 100%가 아니거나 증거금이 {@code long} 범위를 넘는 공모주
     */
    private long validateAndLock(Ipo ipo, Instant receivedAt, SubscribeCommand command) {
        ipo.validateSubscription(receivedAt, command.requestedQuantity());
        ipoRepository.refreshForShare(ipo);
        ipo.validateSubscription(receivedAt, command.requestedQuantity());
        if (!ipo.hasFullMarginRate()) {
            throw new IllegalStateException("증거금률이 100%가 아닌 공모주는 접수할 수 없습니다. ipoId=" + command.ipoId());
        }
        try {
            return Math.multiplyExact(command.requestedQuantity(), ipo.getOfferPrice());
        } catch (ArithmeticException e) {
            throw new IllegalStateException("증거금이 long 범위를 넘습니다: 수량=" + command.requestedQuantity() + ", 공모가=" + ipo.getOfferPrice(), e);
        }
    }

    /**
     * {@code PENDING} 저장이 충돌했을 때(0행) 무엇과 충돌했는지 가림. 정상 접수에서는 부르지 않음.
     * <ul>
     *   <li>같은 {@code requestId}의 청약이 있으면 재요청 비교 (같은 내용이면 저장하지 않고, 다르면 409)</li>
     *   <li>없으면 같은 회원의 유효 청약({@code uk_subscription_active})과 충돌한 것이라 이중청약으로 {@code REJECTED}만 저장함</li>
     *   <li>둘 다 아니면 빈 값. 충돌한 유효 청약이 그사이 끝났거나 모르는 충돌이라, 호출하는 쪽이 다시 저장해 보고 판단함</li>
     * </ul>
     * 충돌한 상대가 커밋 전이면 PostgreSQL이 저장을 그 커밋까지 기다리게 하므로, 0행을 받은 뒤의 조회에서는 상대 행이 보임.
     * 테이블의 유니크 제약은 PK(회원 ID와 requestId로 만든 청약 ID라 request_id 충돌과 같음), {@code request_id}, {@code uk_subscription_active} 세 개뿐임
     * ({@code SubscriptionRepositoryTest}가 지킴). 제약을 더하면 이 판단을 다시 봐야 함.
     */
    private Optional<AcceptResult> resolveConflict(Subscription pending, UUID userId, SubscribeCommand command) {
        Optional<Subscription> existing = subscriptionRepository.findByRequestId(command.requestId());
        if (existing.isPresent()) {
            return Optional.of(replayOrConflict(existing.get(), userId, command));
        }
        if (!subscriptionRepository.existsActiveByIpoIdAndUserId(command.ipoId(), userId)) {
            return Optional.empty();
        }

        Subscription rejected = Subscription.reject(pending.getId(), command.requestId(), pending.getIpo(), userId, command.accountId(),
                command.requestedQuantity(), pending.getMarginAmount(), pending.getReceivedAt(), RejectReason.ALREADY_SUBSCRIBED);
        if (!subscriptionRepository.insertIfAbsent(rejected)) {
            // 그사이 같은 requestId의 요청이 먼저 저장함 (REJECTED는 유효 청약 제약에 걸리지 않으므로 request_id 충돌뿐)
            Subscription saved = subscriptionRepository.findByRequestId(command.requestId())
                    .orElseThrow(() -> new IllegalStateException("저장이 충돌했지만 기존 청약을 찾을 수 없습니다. requestId=" + command.requestId()));
            return Optional.of(replayOrConflict(saved, userId, command));
        }
        log.info("이중청약으로 거절함. subscriptionId={}, userId={}, ipoId={}", rejected.getId(), userId, command.ipoId());
        return Optional.of(AcceptResult.REJECTED_ALREADY_SUBSCRIBED);
    }

    /**
     * 재요청 비교: 같은 {@code requestId}로 다시 온 요청이 처음 저장된 청약과 같은 내용이면 아무것도 저장하지 않고, 다르면 409로 거절함.
     * 다른 회원이 이미 쓴 {@code requestId}도 내용이 다른 것으로 보아 409이고, 응답에는 기존 청약의 내용을 넣지 않음.
     */
    private AcceptResult replayOrConflict(Subscription existing, UUID userId, SubscribeCommand command) {
        if (!existing.isSameRequest(userId, command.accountId(), command.ipoId(), command.requestedQuantity())) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, "이미 사용된 requestId입니다. 새 requestId로 다시 요청해 주세요.", "requestId");
        }
        log.info("같은 내용의 재요청이라 새로 저장하지 않음. requestId={}, subscriptionId={}", command.requestId(), existing.getId());
        return AcceptResult.DUPLICATE_IGNORED;
    }
}
