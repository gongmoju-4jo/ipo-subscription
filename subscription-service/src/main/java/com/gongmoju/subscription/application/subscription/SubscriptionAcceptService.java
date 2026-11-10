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
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionAcceptService {

    private final SubscriptionRequestValidator validator;
    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final MarginReserveRequestRecorder marginReserveRequestRecorder;

    /**
     * 청약 API의 접수를 한 트랜잭션으로 처리함 (D-29 B-2). 검증, 이중청약 판정, 청약 저장, 증거금 인출 요청 Outbox 기록까지 하고 커밋하면 접수가 끝남.
     * <p>
     * 공모주 행을 공유 잠금(FOR SHARE)으로 읽어 접수끼리는 함께 진행하고, 마감(FOR UPDATE)과는 순서를 정함.
     * 마감이 먼저 행을 잡으면 마감이 끝난 뒤 {@code CLOSED}를 읽고 거절되고, 접수가 먼저 잡으면 마감이 이 청약까지 세도록 커밋을 기다림.
     * 같은 {@code requestId}의 청약이 이미 있으면 상태, 기간 검증보다 먼저 확인해, 같은 내용이면 마감 뒤에 다시 와도 처음과 같이 접수된 것으로 응답함.
     *
     * @param userId     Gateway가 넘긴 회원 ID
     * @param receivedAt Gateway가 기록한 최초 도착 시각 (D-12)
     * @param command    청약 신청 내용
     * @return 접수 결과. 새로 접수했거나, 이중청약으로 거절해 저장했거나, 같은 내용의 재요청이라 아무것도 저장하지 않음
     * @throws BusinessException     공모주가 없으면 RESOURCE_NOT_FOUND(404), 같은 {@code requestId}로 내용이 다르거나 다른 회원이 쓴 요청이면
     *                               DUPLICATE_RESOURCE(409), 청약 조건 위반은 {@link Ipo#validateSubscription}
     * @throws IllegalStateException 증거금률이 100%가 아니거나 증거금이 {@code long} 범위를 넘는 공모주 (공모주 데이터 오류, 500)
     */
    @Transactional
    public AcceptResult accept(UUID userId, Instant receivedAt, SubscribeCommand command) {
        Ipo ipo = ipoRepository.findByIdForShare(command.ipoId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "공모주를 찾을 수 없습니다.", "ipoId"));

        Optional<Subscription> existing = subscriptionRepository.findByRequestId(command.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), userId, command);
        }

        ipo.validateSubscription(receivedAt, command.requestedQuantity());
        if (!ipo.hasFullMarginRate()) {
            throw new IllegalStateException("증거금률이 100%가 아닌 공모주는 접수할 수 없습니다. ipoId=" + command.ipoId());
        }
        long marginAmount;
        try {
            marginAmount = Math.multiplyExact(command.requestedQuantity(), ipo.getOfferPrice());
        } catch (ArithmeticException e) {
            throw new IllegalStateException("증거금이 long 범위를 넘습니다: 수량=" + command.requestedQuantity() + ", 공모가=" + ipo.getOfferPrice(), e);
        }

        UUID subscriptionId = SubscriptionIdGenerator.generate(userId, command.requestId());
        boolean alreadySubscribed = subscriptionRepository.existsActiveByIpoIdAndUserId(command.ipoId(), userId);
        Subscription subscription = alreadySubscribed
                ? Subscription.reject(subscriptionId, command.requestId(), ipo, userId, command.accountId(),
                command.requestedQuantity(), marginAmount, receivedAt, RejectReason.ALREADY_SUBSCRIBED)
                : Subscription.accept(subscriptionId, command.requestId(), ipo, userId, command.accountId(),
                command.requestedQuantity(), marginAmount, receivedAt);
        return save(subscription, alreadySubscribed, saved -> replayOrConflict(saved, userId, command));
    }

    /**
     * 접수 Consumer가 받은 청약 요청을 처리함 (이벤트 명세 2-1). 검증, 저장, 증거금 확보 요청 기록을 한 트랜잭션으로 처리함.
     * <p>
     * 동시 요청으로 {@code uk_subscription_active} 위반이 나면 예외를 그대로 전파하여 롤백함.
     * 호출한 쪽(Consumer)이 재시도하고, 재시도에서 기존 청약을 찾아 {@code REJECTED}로 저장함.
     *
     * @param event 접수 내용 (필수 필드와 형식 검증을 통과한 값)
     * @return 저장 결과
     * @throws InvalidEventException {@code subscriptionId} 불일치, 종목 없음, 마감 이후, 증거금률 불일치, 같은 {@code requestId}인데 요청 내용이 다름, 증거금 계산이 {@code long} 범위를 넘음
     */
    @Transactional
    public AcceptResult accept(SubscriptionRequested event) {
        Ipo ipo = validator.validate(event);

        // 같은 requestId의 기존 청약 조회 (재전송 확인)
        Optional<Subscription> existing = subscriptionRepository.findByRequestId(event.requestId());
        if (existing.isPresent()) {
            return ignoreIfSameRequest(existing.get(), event);
        }

        long marginAmount = calculateMarginAmount(event, ipo);
        boolean alreadySubscribed = subscriptionRepository.existsActiveByIpoIdAndUserId(event.ipoId(), event.userId());
        Subscription subscription = alreadySubscribed ? rejectedOf(event, ipo, marginAmount) : pendingOf(event, ipo, marginAmount);
        return save(subscription, alreadySubscribed, saved -> ignoreIfSameRequest(saved, event));
    }

    /**
     * 새 청약을 저장함. 이중청약이면 {@code REJECTED}로만 저장하고, 아니면 {@code PENDING}으로 저장한 뒤 증거금 인출 요청을 Outbox에 기록함.
     * 같은 {@code requestId}의 요청이 동시에 먼저 저장했으면 그 행을 다시 읽어 {@code onRequestIdConflict}로 재요청 비교를 이어 감.
     */
    private AcceptResult save(Subscription subscription, boolean alreadySubscribed,
                              Function<Subscription, AcceptResult> onRequestIdConflict) {
        if (!subscriptionRepository.insertIfAbsent(subscription)) {
            Subscription saved = subscriptionRepository.findByRequestId(subscription.getRequestId())
                    .orElseThrow(() -> new IllegalStateException("저장이 충돌했지만 기존 청약을 찾을 수 없습니다. requestId=" + subscription.getRequestId()));
            return onRequestIdConflict.apply(saved);
        }
        if (alreadySubscribed) {
            log.info("이중청약으로 거절함. subscriptionId={}, userId={}, ipoId={}",
                    subscription.getId(), subscription.getUserId(), subscription.getIpo().getId());
            return AcceptResult.REJECTED_ALREADY_SUBSCRIBED;
        }
        marginReserveRequestRecorder.record(subscription);
        return AcceptResult.ACCEPTED;
    }

    /**
     * API 재요청 비교: 같은 {@code requestId}로 다시 온 요청이 처음 저장된 청약과 같은 내용이면 아무것도 저장하지 않고, 다르면 409로 거절함.
     * 다른 회원이 이미 쓴 {@code requestId}도 내용이 다른 것으로 보아 409이고, 응답에는 기존 청약의 내용을 넣지 않음.
     */
    private AcceptResult replayOrConflict(Subscription existing, UUID userId, SubscribeCommand command) {
        if (!existing.isSameRequest(userId, command.accountId(), command.ipoId(), command.requestedQuantity())) {
            throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, "이미 사용된 requestId입니다. 새 requestId로 다시 요청해 주세요.", "requestId");
        }
        log.info("같은 내용의 재요청이라 새로 저장하지 않음. requestId={}, subscriptionId={}", command.requestId(), existing.getId());
        return AcceptResult.DUPLICATE_IGNORED;
    }

    /**
     * 재전송 내용 비교: 같은 {@code requestId}로 다시 온 요청이 처음 저장된 청약과 같은 내용이면 무시하고, 다르면 예외를 던짐.
     *
     * @throws InvalidEventException 같은 {@code requestId}인데 요청 내용이 다른 경우
     */
    private AcceptResult ignoreIfSameRequest(Subscription existing, SubscriptionRequested event) {
        if (!existing.isSameRequest(event.userId(), event.accountId(), event.ipoId(), event.requestedQuantity())) {
            throw new InvalidEventException("같은 requestId인데 요청 내용이 다릅니다: " + event.requestId());
        }
        log.info("같은 내용의 재전송이라 무시함. requestId={}, subscriptionId={}", event.requestId(), event.subscriptionId());
        return AcceptResult.DUPLICATE_IGNORED;
    }

    /**
     * 증거금률 100%라 청약 금액과 같음 (종목 검증에서 증거금률 확인). long 범위를 넘으면 재처리해도 같은 결과이므로 재시도 없는 예외로 처리함.
     */
    private long calculateMarginAmount(SubscriptionRequested event, Ipo ipo) {
        try {
            return Math.multiplyExact(event.requestedQuantity(), ipo.getOfferPrice());
        } catch (ArithmeticException e) {
            throw new InvalidEventException("증거금이 long 범위를 넘습니다: 수량=" + event.requestedQuantity() + ", 공모가=" + ipo.getOfferPrice());
        }
    }

    private Subscription pendingOf(SubscriptionRequested event, Ipo ipo, long marginAmount) {
        return Subscription.accept(event.subscriptionId(), event.requestId(), ipo, event.userId(), event.accountId(),
                event.requestedQuantity(), marginAmount, event.receivedAt());
    }

    private Subscription rejectedOf(SubscriptionRequested event, Ipo ipo, long marginAmount) {
        return Subscription.reject(event.subscriptionId(), event.requestId(), ipo, event.userId(), event.accountId(),
                event.requestedQuantity(), marginAmount, event.receivedAt(), RejectReason.ALREADY_SUBSCRIBED);
    }
}
