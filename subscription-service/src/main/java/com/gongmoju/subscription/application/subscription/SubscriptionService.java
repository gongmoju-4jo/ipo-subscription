package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.util.PageableUtils;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequestedPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    // 목록에서 정렬할 수 있는 필드 (API 명세 1.4: 정렬 필드는 API별로 제한)
    private static final Set<String> LIST_SORTABLE_FIELDS = Set.of("createdAt");

    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;
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

    /**
     * 회원 본인의 청약 목록을 조회한다. 거절, 취소된 청약도 포함한다.
     * 정렬을 지정하지 않으면 등록 시각 최신순이고, 같은 시각이면 청약 ID 순이다.
     * 접수 Consumer가 아직 저장하지 않은 청약은 목록에 없다.
     *
     * @param userId   Gateway가 넘긴 회원 ID
     * @param pageable 페이지 정보
     * @return 청약 페이지. 없으면 빈 페이지
     * @throws BusinessException 정렬할 수 없는 필드면 VALIDATION_ERROR(400)
     */
    @Transactional(readOnly = true)
    public Page<SubscriptionResult> list(UUID userId, Pageable pageable) {
        return subscriptionRepository.findByUserId(userId, PageableUtils.toStablePageable(pageable, LIST_SORTABLE_FIELDS))
                .map(SubscriptionResult::from);
    }

    /**
     * 회원 본인의 청약 한 건을 조회한다.
     * 다른 회원의 청약은 존재 여부를 숨기고 없는 청약과 같은 404로 응답한다 (API 명세 1.6).
     * 202 직후 접수 Consumer가 아직 저장하지 않은 청약도 404다 (D-20, 클라이언트가 재조회).
     *
     * @param userId         Gateway가 넘긴 회원 ID
     * @param subscriptionId 청약 ID
     * @return 청약
     * @throws BusinessException 없거나 본인 청약이 아니면 RESOURCE_NOT_FOUND(404)
     */
    @Transactional(readOnly = true)
    public SubscriptionResult get(UUID userId, UUID subscriptionId) {
        return subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .map(SubscriptionResult::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "청약 내역을 찾을 수 없습니다.", "subscriptionId"));
    }
}
