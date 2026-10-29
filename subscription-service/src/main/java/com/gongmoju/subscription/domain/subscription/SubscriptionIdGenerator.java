package com.gongmoju.subscription.domain.subscription;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 청약 ID 생성 규칙 (이벤트 명세 결정 11). 같은 회원이 같은 requestId로 다시 요청하면 DB 조회 없이 같은 ID가 나온다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SubscriptionIdGenerator {

    /**
     * {@code "{userId}:{requestId}"}(소문자 UUID 두 개를 콜론으로 이은 UTF-8 문자열)의 MD5로 UUID v3를 만든다.
     * 접수 Consumer 등 다른 곳에서 계산해도 같은 값이 나오도록 문자열과 순서를 바꾸지 않는다.
     *
     * @param userId    회원 ID
     * @param requestId 클라이언트 요청 ID
     * @return 청약 ID (UUID v3)
     */
    public static UUID generate(UUID userId, UUID requestId) {
        String name = userId + ":" + requestId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
