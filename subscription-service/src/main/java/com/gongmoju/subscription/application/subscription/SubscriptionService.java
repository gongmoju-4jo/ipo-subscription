package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequestedPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final IpoRepository ipoRepository;
    private final SubscriptionRequestedPublisher subscriptionRequestedPublisher;

    /**
     * 청약 신청을 사전 검증한 뒤 {@code subscription.requested}로 발행한다. 청약 행은 접수 Consumer가 만든다.
     * <p>
     * 트랜잭션을 걸지 않는다. 공모주 조회만 하고, 브로커 저장 확인을 기다리는 동안 DB 커넥션을 잡고 있지 않기 위해서다.
     * 같은 requestId로 다시 요청하면 같은 subscriptionId로 다시 발행하고, 중복은 접수 Consumer가 request_id로 걸러낸다.
     *
     * @param userId     Gateway가 넘긴 회원 ID
     * @param receivedAt Gateway가 기록한 최초 도착 시각 (D-12)
     * @param command    청약 신청 내용
     * @return 청약 ID와 요청 ID
     * @throws BusinessException 공모주가 없으면 RESOURCE_NOT_FOUND(404), 청약 조건 위반은 {@link Ipo#validateSubscription},
     *                           브로커 저장을 확인하지 못하면 INTERNAL_SERVER_ERROR(500)
     */
    public SubscribeResult subscribe(UUID userId, Instant receivedAt, SubscribeCommand command) {
        Ipo ipo = ipoRepository.findById(command.ipoId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "공모주를 찾을 수 없습니다.", "ipoId"));
        ipo.validateSubscription(receivedAt, command.requestedQuantity());

        UUID subscriptionId = SubscriptionIdGenerator.generate(userId, command.requestId());
        subscriptionRequestedPublisher.publish(new SubscriptionRequested(command.requestId(), subscriptionId, userId,
                command.accountId(), command.ipoId(), command.requestedQuantity(), receivedAt));
        return new SubscribeResult(subscriptionId, command.requestId());
    }
}
