package com.gongmoju.account.domain.margin;

/**
 * 증거금 인출 결과 이벤트 발행을 위한 인터페이스.
 */
public interface MarginEventPublisher {

    void publishReserved(MarginReserved event);

    void publishReserveFailed(MarginReserveFailed event);
}
