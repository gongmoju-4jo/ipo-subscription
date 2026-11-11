package com.gongmoju.gateway.queue;

/**
 * 공모주 하나의 입장 처리 결과.
 *
 * @param admitted   이번에 허가한 인원
 * @param droppedOut 이번에 정리한 이탈자 (이탈 기한이 지난 사람)
 * @param remaining  남은 대기 인원
 */
public record AdmitResult(long admitted, long droppedOut, long remaining) {
}
