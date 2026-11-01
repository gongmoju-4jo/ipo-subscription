package com.gongmoju.subscription.presentation.support;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiErrorDetail;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 목록 API의 page, size 요청 값을 검증한다.
 * <p>
 * 공통 Pageable 해석은 범위를 벗어난 값을 조용히 기본값/최대값으로 바꾼다. API 명세 v1.1은 400이므로 원래 요청 값을 여기서 직접 검증한다
 * (숫자가 아닌 값은 Integer 변환에서 먼저 400이 된다). message는 요약, details의 reason은 필드별 사유 (API 명세 3.3 실패 예시).
 * offset(page x size)이 int 범위를 넘으면 JPA가 예외를 던져 500이 되므로 page 상한을 size로 계산한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class PagingValidator {

    /**
     * @param page            요청한 page (없으면 null)
     * @param size            요청한 size (없으면 null)
     * @param appliedPageSize 실제로 적용할 페이지 크기 (1 이상). page 상한 계산에 쓴다
     * @param maxPageSize     허용하는 size 최대값
     * @throws BusinessException page나 size가 범위를 벗어나면 VALIDATION_ERROR(400)
     */
    public static void validate(Integer page, Integer size, int appliedPageSize, int maxPageSize) {
        int maxPage = Integer.MAX_VALUE / appliedPageSize;
        if (page != null && (page < 0 || page > maxPage)) {
            throw pagingError("page", "페이지 번호는 0 이상 " + maxPage + " 이하여야 합니다.");
        }
        if (size != null && (size < 1 || size > maxPageSize)) {
            throw pagingError("size", "페이지 크기는 1 이상 " + maxPageSize + " 이하여야 합니다.");
        }
    }

    private static BusinessException pagingError(String field, String reason) {
        return new BusinessException(CommonErrorCode.VALIDATION_ERROR, "페이지 요청 값이 올바르지 않습니다.",
                List.of(new ApiErrorDetail(field, reason)));
    }
}
