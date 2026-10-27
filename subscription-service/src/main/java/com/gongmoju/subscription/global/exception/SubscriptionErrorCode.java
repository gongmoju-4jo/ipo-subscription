package com.gongmoju.subscription.global.exception;

import com.gongmoju.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// subscription-service 도메인 에러 코드. 공통 에러로 표현할 수 없는 것만 추가하고 API 명세 오류 표에도 같이 적는다
// 예: INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "예수금이 부족합니다."),
@Getter
@RequiredArgsConstructor
public enum SubscriptionErrorCode implements ErrorCode {
    // 공모주가 OPEN이 아니어서 청약 불가, 내부 API에서 공모주가 아직 CLOSED가 아님
    INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없습니다."),
    // 청약이 아직 처리 중(PENDING)이거나 다른 처리와 동시에 변경됨
    SUBSCRIPTION_PROCESSING(HttpStatus.CONFLICT, "청약을 처리 중입니다. 잠시 후 다시 시도해주세요."),
    // 취소할 수 없는 상태 (REJECTED, COMPENSATING, 처리 실패로 해제된 CANCELLED)
    NOT_CANCELLABLE(HttpStatus.CONFLICT, "취소할 수 없는 청약입니다."),
    // 청약 시작 전 또는 마감 시각 이후
    SUBSCRIPTION_CLOSED(HttpStatus.UNPROCESSABLE_ENTITY, "청약 가능 시간이 아닙니다.");

    private final HttpStatus status;
    private final String message;
}
