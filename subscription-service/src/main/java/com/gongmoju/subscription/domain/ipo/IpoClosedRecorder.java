package com.gongmoju.subscription.domain.ipo;

/**
 * 마감 이벤트({@code ipo.closed}) 기록. 구현은 infrastructure/messaging (Outbox).
 */
public interface IpoClosedRecorder {

    /**
     * 마감(OPEN → CLOSED)과 같은 트랜잭션 안에서 호출해야 함. 마감이 롤백되면 기록도 함께 롤백됨.
     *
     * @param ipo            방금 {@code CLOSED}로 바꾼 공모주
     * @param confirmedCount 배정 대상 {@code CONFIRMED} 청약 건수
     */
    void record(Ipo ipo, long confirmedCount);
}
