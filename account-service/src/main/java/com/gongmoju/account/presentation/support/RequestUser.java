package com.gongmoju.account.presentation.support;

import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * 요청자 식별과 응답 시각 변환. 컨트롤러가 함께 쓴다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RequestUser {

    public static final String HEADER = "X-User-Id";

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /**
     * Gateway가 JWT 검증 후 넣어 준 회원 ID를 읽는다. 헤더가 없거나 UUID가 아니면 Gateway를 거치지 않은 요청이므로 401로 응답한다.
     * (헤더가 없는 채로 진행하면 Audit이 요청자를 SYSTEM:account-service로 기록해 사람의 요청과 구분할 수 없다)
     *
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
     * 서버는 UTC(Instant)로 저장하고 응답에서만 KST로 바꾼다. 초 단위까지만 내려준다.
     * <p>
     * 방금 만든 값은 메모리의 나노초 정밀도이고 DB에서 읽은 값은 마이크로초(반올림)라서, 그대로 내려주면 같은 계좌의
     * 첫 응답과 재전송 응답의 시각이 마지막 자리에서 달라진다. API 명세 예시도 초 단위다.
     *
     * @param instant 저장된 시각
     * @return KST(+09:00) 시각 (초 단위)
     */
    public static OffsetDateTime toKst(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atOffset(KST);
    }
}
