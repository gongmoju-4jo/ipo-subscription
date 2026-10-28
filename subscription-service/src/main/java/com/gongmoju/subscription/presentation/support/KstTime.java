package com.gongmoju.subscription.presentation.support;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 응답 시각 변환. 서버는 UTC(Instant)로 저장하고 응답에서만 KST로 바꾼다 (계좌 서비스 RequestUser.toKst와 같은 규칙).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class KstTime {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    /**
     * 초 단위까지만 내려준다. DB에서 읽은 값은 마이크로초라서 그대로 내려주면 같은 값도 응답마다 자릿수가 달라질 수 있다.
     *
     * @param instant 저장된 시각
     * @return KST(+09:00) 시각 (초 단위)
     */
    public static OffsetDateTime of(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS).atOffset(KST);
    }
}
