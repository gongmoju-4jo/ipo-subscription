package com.gongmoju.common.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 공통 목록 응답. Page를 그대로 반환하지 않고 이 형식으로 변환
 * <p>
 * 기본값 page=0, size=20(최대 100), 정렬 createdAt DESC는 CommonWebAutoConfiguration에서 설정한다.
 *
 * @param items         목록
 * @param page          현재 페이지 (0부터 시작)
 * @param size          페이지 크기
 * @param totalElements 전체 개수
 * @param totalPages    전체 페이지 수
 * @param <T>           목록 항목 타입
 */
@Schema(description = "목록 응답")
public record PageResponse<T>(
        @Schema(description = "목록")
        List<T> items,
        @Schema(description = "현재 페이지 (0부터 시작)", example = "0")
        int page,
        @Schema(description = "페이지 크기", example = "20")
        int size,
        @Schema(description = "전체 개수", example = "0")
        long totalElements,
        @Schema(description = "전체 페이지 수", example = "0")
        int totalPages
) {

    /**
     * Page를 목록 응답으로 바꾼다.
     *
     * @param page 조회 결과
     * @param <T>  목록 항목 타입
     * @return 목록 응답
     */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /**
     * 엔티티 Page를 DTO 목록으로 바꿀 때 사용: {@code PageResponse.from(subscriptionPage, SubscriptionResponse::from)}
     *
     * @param page   엔티티 조회 결과
     * @param mapper 엔티티 → DTO 변환 함수
     * @param <E>    엔티티 타입
     * @param <T>    DTO 타입
     * @return 목록 응답
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
