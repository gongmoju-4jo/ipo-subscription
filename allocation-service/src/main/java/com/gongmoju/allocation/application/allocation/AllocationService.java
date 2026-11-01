package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationRepository;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AllocationService {

    private final AllocationRepository allocationRepository;

    /**
     * 본인 청약 1건의 배정 결과를 조회한다.
     * 배정 결과는 배정 완료(round DONE)와 같은 트랜잭션에서 저장되므로, 조회되면 항상 배정이 끝난 결과다.
     *
     * @param subscriptionId 청약 ID
     * @param userId         요청자 회원 ID (X-User-Id)
     * @return 배정 결과
     * @throws BusinessException 배정 전, 배정 대상이 아닌 청약, 본인 청약이 아니면 RESOURCE_NOT_FOUND(404).
     *                           남의 청약도 존재를 숨기고 같은 404로 응답한다 (API 명세 1.6, 3.5)
     */
    @Transactional(readOnly = true)
    public AllocationResult get(UUID subscriptionId, UUID userId) {
        return allocationRepository.findBySubscriptionIdAndUserId(subscriptionId, userId)
                .map(AllocationResult::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "배정 결과를 찾을 수 없습니다.", "subscriptionId"));
    }
}
