package com.gongmoju.account.domain.margin;

// 증거금 응답 이벤트 발행. 구현은 infrastructure/messaging (Outbox에 저장, Kafka 전송은 OutboxPublisher)
// 업무 변경과 같은 트랜잭션에서 호출한다
public interface MarginEventPublisher {

    void publishReserved(MarginReserved event);

    void publishReserveFailed(MarginReserveFailed event);
}
