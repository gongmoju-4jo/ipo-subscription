package com.gongmoju.account.application.margin;

/**
 * {@link MarginReserveService#reserveAll} 결과 건수 (배치 처리 로그용)
 *
 * @param reserved 인출한 건
 * @param rejected 거절 응답(margin.reserve-failed)을 남긴 건
 * @param skipped  중복 수신, 재요청, 선행 반환 기록으로 인출하지 않은 건
 */
public record ReserveBatchResult(int reserved, int rejected, int skipped) {
}
