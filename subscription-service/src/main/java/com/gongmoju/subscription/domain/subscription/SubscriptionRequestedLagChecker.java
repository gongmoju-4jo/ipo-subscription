package com.gongmoju.subscription.domain.subscription;

// 접수 토픽(subscription.requested)에 남은 메시지 수 확인 (마감 조건, D-16). 구현은 infrastructure/messaging (Kafka AdminClient)
public interface SubscriptionRequestedLagChecker {

    /**
     * 접수 Consumer 그룹이 아직 처리하지 않은 메시지 수(모든 파티션의 랙 합계). 키가 userId라서 공모주별로 나누지 않고 토픽 전체로 본다.
     *
     * @return 랙 합계. 0이면 받은 접수 메시지를 모두 처리함
     * @throws RuntimeException Kafka에서 오프셋을 읽지 못한 경우
     */
    long currentLag();
}
