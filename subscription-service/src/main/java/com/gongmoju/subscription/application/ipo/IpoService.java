package com.gongmoju.subscription.application.ipo;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.util.PageableUtils;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IpoService {

    // 목록에서 정렬할 수 있는 필드 (API 명세 1.4: 정렬 필드는 API별로 제한)
    private static final Set<String> LIST_SORTABLE_FIELDS = Set.of("createdAt");

    private final IpoRepository ipoRepository;

    /**
     * 공모주 목록을 조회한다. 청약 시작 전, 진행 중, 마감된 종목을 모두 돌려준다.
     * 정렬을 지정하지 않으면 등록 시각 최신순이고, 같은 시각이면 ID 순이다 (시드는 등록 시각이 모두 같다).
     *
     * @param pageable 페이지 정보
     * @return 공모주 페이지. 없으면 빈 페이지
     * @throws BusinessException 정렬할 수 없는 필드면 VALIDATION_ERROR(400)
     */
    @Transactional(readOnly = true)
    public Page<IpoResult> list(Pageable pageable) {
        return ipoRepository.findAll(PageableUtils.toStablePageable(pageable, LIST_SORTABLE_FIELDS))
                .map(IpoResult::from);
    }

    /**
     * 상태가 같은 공모주를 모두 조회한다. 게이트웨이가 청약 중 공모주 목록을 주기적으로 받아 가는 내부 API(4.4)용이다.
     * OPEN 공모주는 동시에 몇 개뿐이라 페이지로 나누지 않는다.
     *
     * @param status 공모주 상태
     * @return 공모주 목록. 없으면 빈 목록. 순서는 의미 없음
     */
    @Transactional(readOnly = true)
    public List<IpoResult> listByStatus(IpoStatus status) {
        return ipoRepository.findByStatus(status).stream().map(IpoResult::from).toList();
    }

    /**
     * 공모주 한 건을 조회한다.
     *
     * @param ipoId 공모주 ID
     * @return 공모주
     * @throws BusinessException 없으면 RESOURCE_NOT_FOUND(404)
     */
    @Transactional(readOnly = true)
    public IpoResult get(UUID ipoId) {
        return ipoRepository.findById(ipoId)
                .map(IpoResult::from)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "공모주를 찾을 수 없습니다.", "ipoId"));
    }
}
