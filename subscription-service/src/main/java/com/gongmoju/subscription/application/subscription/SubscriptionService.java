package com.gongmoju.subscription.application.subscription;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.util.PageableUtils;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    // 목록에서 정렬할 수 있는 필드 (API 명세 1.4: 정렬 필드는 API별로 제한)
    private static final Set<String> LIST_SORTABLE_FIELDS = Set.of("createdAt");

    private final IpoRepository ipoRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionAcceptService acceptService;

    /**
     * 청약 신청을 접수한다 (D-29 B-2). 한 트랜잭션에서 검증, 이중청약 판정, 청약 저장, 증거금 인출 요청 Outbox 기록을 마치면 접수가 끝난다.
     * 이중청약이면 {@code REJECTED}로 저장하고, 같은 {@code requestId}로 같은 내용이 다시 오면 새로 저장하지 않는다. 세 경우 모두 같은 청약 ID를 돌려준다.
     * 트랜잭션은 {@link SubscriptionAcceptService#accept(UUID, UUID, Instant, SubscribeCommand)}가 건다.
     * <p>
     * 같은 {@code requestId}, 같은 회원의 유효 청약({@code uk_subscription_active}) 충돌은 저장 단계에서 0행으로 받아 처리해 예외가 나지 않는다.
     * 같은 회원이 다른 {@code requestId}로 거의 동시에 청약해도 늦은 쪽은 앞 청약의 커밋을 기다린 뒤 0행을 받아 {@code REJECTED}로 저장된다.
     *
     * @param userId     Gateway가 넘긴 회원 ID
     * @param receivedAt Gateway가 기록한 최초 도착 시각 (D-12)
     * @param command    청약 신청 내용
     * @return 청약 ID와 요청 ID
     * @throws BusinessException     공모주가 없으면 RESOURCE_NOT_FOUND(404), 같은 {@code requestId}로 내용이 다르거나 다른 회원이 쓴 요청이면
     *                               DUPLICATE_RESOURCE(409), 청약 조건 위반은 {@link Ipo#validateSubscription}
     * @throws IllegalStateException 증거금률이 100%가 아니거나 증거금이 {@code long} 범위를 넘는 공모주 (500)
     */
    public SubscribeResult subscribe(UUID userId, Instant receivedAt, SubscribeCommand command) {
        UUID subscriptionId = SubscriptionIdGenerator.generate(userId, command.requestId());
        acceptService.accept(subscriptionId, userId, receivedAt, command);
        return new SubscribeResult(subscriptionId, command.requestId());
    }

    /**
     * 회원 본인의 청약 목록을 조회한다. 거절, 취소된 청약도 포함한다.
     * 정렬을 지정하지 않으면 등록 시각 최신순이고, 같은 시각이면 청약 ID 순이다.
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

    /**
     * 배정 서비스가 가져갈 공모주의 확정 청약 목록을 조회한다 (내부 API, API 명세 4.1).
     * 마감(CLOSED) 이후에만 조회할 수 있어서 페이지를 나눠 받는 동안 목록이 바뀌지 않는다.
     * 정렬은 청약 ID 오름차순 고정이다. 페이지 사이 중복, 누락을 막고, 배정 추첨의 입력 순서가 된다.
     *
     * @param ipoId 공모주 ID
     * @param page  페이지 번호 (검증된 값)
     * @param size  페이지 크기 (검증된 값)
     * @return 확정 청약 페이지. 없으면 빈 페이지
     * @throws BusinessException 공모주가 없으면 RESOURCE_NOT_FOUND(404), CLOSED가 아니면 INVALID_STATUS_TRANSITION(409)
     */
    @Transactional(readOnly = true)
    public Page<ConfirmedSubscriptionResult> listConfirmed(UUID ipoId, int page, int size) {
        validateClosedIpo(ipoId);

        return subscriptionRepository.findByIpoIdAndStatus(ipoId, SubscriptionStatus.CONFIRMED,
                        PageRequest.of(page, size, Sort.by("id")))
                .map(ConfirmedSubscriptionResult::from);
    }

    /**
     * 배정 서비스가 가져갈 공모주의 확정 청약 목록을 키셋 방식으로 조회한다 (내부 API, API 명세 4.1, #138).
     * 직전 응답의 마지막 청약 ID 다음부터 읽어서, 뒤쪽 목록도 앞 행을 건너뛰며 세지 않는다.
     * 마감(CLOSED) 이후에만 조회할 수 있어서 나눠 받는 동안 목록이 바뀌지 않는다. 정렬은 청약 ID 오름차순 고정이다.
     *
     * @param ipoId               공모주 ID
     * @param afterSubscriptionId 직전 응답의 마지막 청약 ID. 첫 요청은 null
     * @param size                페이지 크기 (검증된 값)
     * @return 확정 청약 목록, 다음 목록이 있는지, 첫 요청이면 전체 건수
     * @throws BusinessException 공모주가 없으면 RESOURCE_NOT_FOUND(404), CLOSED가 아니면 INVALID_STATUS_TRANSITION(409)
     */
    @Transactional(readOnly = true)
    public ConfirmedSubscriptionKeysetResult listConfirmedAfter(UUID ipoId, UUID afterSubscriptionId, int size) {
        validateClosedIpo(ipoId);

        // 한 건 더 읽어 다음 목록이 있는지 판단한다 (전체 건수를 세지 않음)
        List<Subscription> rows = afterSubscriptionId == null
                ? subscriptionRepository.findConfirmedByIpoId(ipoId, size + 1)
                : subscriptionRepository.findConfirmedByIpoIdAfter(ipoId, afterSubscriptionId, size + 1);
        boolean hasNext = rows.size() > size;
        List<ConfirmedSubscriptionResult> items = rows.stream()
                .limit(size)
                .map(ConfirmedSubscriptionResult::from)
                .toList();
        // 전체 건수는 배정 서비스가 다 받은 건수를 확인하는 데 한 번만 쓴다. 매 요청 세면 다시 느려진다
        Long totalElements = afterSubscriptionId == null
                ? subscriptionRepository.countByIpoIdAndStatusIn(ipoId, Set.of(SubscriptionStatus.CONFIRMED))
                : null;
        return new ConfirmedSubscriptionKeysetResult(items, hasNext, totalElements);
    }

    private void validateClosedIpo(UUID ipoId) {
        Ipo ipo = ipoRepository.findById(ipoId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "공모주를 찾을 수 없습니다.", "ipoId"));
        ipo.validateClosed();
    }
}
