package com.gongmoju.subscription.domain.subscription;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionIdGeneratorTest {

    @Test
    void 이벤트_명세_예시와_같은_청약_ID를_만든다() {
        // 이벤트 명세 2-1 subscription.requested 예시 (결정 11: "{userId}:{requestId}"의 UUID v3)
        UUID userId = UUID.fromString("0192a000-3333-7eee-8fff-000000000003");
        UUID requestId = UUID.fromString("0192c1a0-7b3e-7c11-9a20-5f3d2e1b4c88");

        UUID subscriptionId = SubscriptionIdGenerator.generate(userId, requestId);

        assertThat(subscriptionId).isEqualTo(UUID.fromString("655082e4-0c53-3ea4-be64-ed0b852dd9aa"));
        assertThat(subscriptionId.version()).isEqualTo(3);
    }

    @Test
    void 같은_회원과_요청_ID면_같은_ID이고_하나라도_다르면_다른_ID다() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        assertThat(SubscriptionIdGenerator.generate(userId, requestId))
                .isEqualTo(SubscriptionIdGenerator.generate(userId, requestId))
                .isNotEqualTo(SubscriptionIdGenerator.generate(UUID.randomUUID(), requestId))
                .isNotEqualTo(SubscriptionIdGenerator.generate(userId, UUID.randomUUID()));
    }
}
