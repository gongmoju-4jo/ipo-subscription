package com.gongmoju.subscription.domain.subscription;

// 청약 신청 접수 발행. 구현은 infrastructure/messaging (Kafka 직접 발행, Outbox 없음 D-13)
public interface SubscriptionRequestedPublisher {

    /**
     * 브로커 저장 확인(acks=all)까지 기다린다. 저장을 확인하지 못하면 예외를 던진다.
     *
     * @param event 접수 내용
     */
    void publish(SubscriptionRequested event);
}
