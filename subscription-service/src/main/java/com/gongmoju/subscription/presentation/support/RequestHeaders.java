package com.gongmoju.subscription.presentation.support;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Gateway가 JWT 검증 후 넣어 주는 헤더를 읽는다 (API 명세 1.1). 클라이언트가 보낸 값은 Gateway가 지우고 다시 넣는다.
 * 헤더가 없거나 형식이 틀리면 Gateway를 거치지 않은 요청으로 보고 401로 응답한다 (계좌 서비스 RequestUser와 같은 규칙).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String RECEIVED_AT = "X-Request-Received-At";

    /**
     * @param header {@code X-User-Id} 헤더 값
     * @return 요청자 회원 ID
     */
    public static UUID userId(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * @param header {@code X-Request-Received-At} 헤더 값. UTC ISO-8601, 밀리초까지 (예: {@code 2026-11-02T00:59:59.123Z})
     * @return Gateway에 요청이 처음 도착한 시각 (D-12)
     */
    public static Instant receivedAt(String header) {
        if (header == null || header.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return Instant.parse(header.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
