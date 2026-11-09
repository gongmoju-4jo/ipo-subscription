package com.gongmoju.gateway.queue;

/**
 * 대기열에서 내 상태.
 */
public enum QueueStatus {
    /** 줄에서 기다리는 중 */
    WAITING,
    /** 입장 허가를 받음. 허가 유효 시간 안에 청약 신청할 수 있다 */
    ADMITTED
}
