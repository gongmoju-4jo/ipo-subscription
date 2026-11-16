package com.gongmoju.subscription.domain.ipo;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 경쟁률(F-15) 계산에 쓰는 공모주별 배정 대상 신청 수량 합의 빠른 사본 (구현: Redis).
 * 기준은 DB의 청약 행이고, 이 사본은 조회마다 DB에서 합산하지 않으려고 둔다.
 * <p>
 * 사본이 없으면(처음, 저장소 재시작, 유효 시간 만료) 호출하는 쪽이 DB로 다시 계산해 채운다. 채우는 순서:
 * {@link #startLoading}(채우는 중 표시) → DB 합산 → {@link #finishLoading}(DB 합산 + 그사이 들어온 확정). 채우는 동안 확정된 청약이 빠지지 않게 하려는 순서다.
 * 표시 직후 DB 합산이 시작되기 전(수 ms)에 커밋된 확정은 DB 합산과 그사이 합계에 함께 들어가 두 번 셀 수 있다 (사본 유효 시간이 지나 DB로 다시 계산할 때 바로잡힘).
 * 어떤 메서드도 저장소 오류를 예외로 던지지 않는다 (경쟁률 때문에 청약 확정이나 조회가 실패하지 않게). 오류 중에는 사본이 없는 것처럼 동작한다.
 */
public interface CompetitionRateCounter {

    /**
     * 확정된 청약의 신청 수량을 더한다. 확정이 DB에 커밋된 뒤에 부른다.
     * 사본이 있으면 사본에, 채우는 중이면 채우는 중 합계에 더하고, 둘 다 없으면 더하지 않는다 (다음 조회가 DB로 채우면 그 안에 들어 있음).
     *
     * @param ipoId 공모주 ID
     * @param requestedQuantity 확정된 청약의 신청 수량
     */
    void addConfirmed(UUID ipoId, long requestedQuantity);

    /**
     * 공모주별 신청 수량 합을 읽는다.
     *
     * @param ipoIds 공모주 ID
     * @return 공모주 ID → 신청 수량 합. 사본이 없거나 읽지 못한 공모주는 들어 있지 않음
     */
    Map<UUID, Long> find(Collection<UUID> ipoIds);

    /**
     * 사본을 채우기 시작한다고 표시한다. 표시한 뒤의 확정은 채우는 중 합계에 모인다. DB 합산보다 먼저 부른다.
     * 다른 쪽(다른 인스턴스)이 이미 채우는 중인 공모주는 맡지 않는다.
     *
     * @param ipoIds 사본이 없는 공모주 ID
     * @return 이 호출이 채우기를 맡은 공모주 ID. 이 공모주만 {@link #finishLoading}으로 넣는다. 저장소 오류면 빈 집합
     */
    Set<UUID> startLoading(Collection<UUID> ipoIds);

    /**
     * 맡은 공모주의 사본을 DB 합산 + 채우는 중 합계로 넣고 채우는 중 표시를 지운다. 그사이 사본이 생겼으면 그대로 둔다.
     *
     * @param requestedQuantities 맡은 공모주 ID → DB로 합산한 신청 수량 합 ({@link #startLoading} 뒤에 합산한 값)
     */
    void finishLoading(Map<UUID, Long> requestedQuantities);
}
