package com.gongmoju.account.domain.margin;

/**
 * 증거금 인출 및 반환 처리 결과 이벤트를 발행하는 인터페이스.
 */
public interface MarginEventPublisher {

    void publishReserved(MarginReserved event);

    void publishReserveFailed(MarginReserveFailed event);

    void publishVoided(MarginVoided event);

    void publishSettled(MarginSettled event);
}
