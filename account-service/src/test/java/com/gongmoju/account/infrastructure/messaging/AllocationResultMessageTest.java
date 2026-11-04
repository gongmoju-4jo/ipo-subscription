package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.SettleMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// allocation.result 메시지 검증. 재처리해도 결과가 같은 실패는 InvalidEventException (바로 DLT)
class AllocationResultMessageTest {

    private static final String TOPIC = "allocation.result";
    private static final String OCCURRED_AT = "2026-10-27T16:20:00.000+09:00";

    private final UUID eventId = UUID.randomUUID();
    private final UUID ipoId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void 정상_메시지는_정산_명령으로_바뀐다() {
        SettleMarginCommand command = message(1L, 2L, 3L, 45_000L).toCommand();

        assertThat(command).isEqualTo(new SettleMarginCommand(eventId, subscriptionId, accountId, ipoId, 45_000));
    }

    @Test
    void 영주_배정도_정산_명령으로_바뀐다() {
        assertThat(message(0L, 0L, 0L, 0L).toCommand().allocatedAmount()).isZero();
    }

    @Test
    void eventType이_토픽과_다르면_거절한다() {
        assertInvalid(new AllocationResultMessage(eventId, "allocation.finished", OCCURRED_AT, ipoId, subscriptionId, accountId,
                1L, 2L, 3L, 45_000L), "eventType");
    }

    @Test
    void 시각_형식이_틀리면_거절한다() {
        assertInvalid(new AllocationResultMessage(eventId, TOPIC, "2026-10-27 16:20:00", ipoId, subscriptionId, accountId,
                1L, 2L, 3L, 45_000L), "occurredAt");
    }

    @Test
    void 필수_필드가_없으면_거절한다() {
        assertInvalid(new AllocationResultMessage(null, TOPIC, OCCURRED_AT, ipoId, subscriptionId, accountId, 1L, 2L, 3L, 45_000L), "eventId");
        assertInvalid(new AllocationResultMessage(eventId, TOPIC, null, ipoId, subscriptionId, accountId, 1L, 2L, 3L, 45_000L), "occurredAt");
        assertInvalid(new AllocationResultMessage(eventId, TOPIC, OCCURRED_AT, null, subscriptionId, accountId, 1L, 2L, 3L, 45_000L), "ipoId");
        assertInvalid(new AllocationResultMessage(eventId, TOPIC, OCCURRED_AT, ipoId, null, accountId, 1L, 2L, 3L, 45_000L), "subscriptionId");
        assertInvalid(new AllocationResultMessage(eventId, TOPIC, OCCURRED_AT, ipoId, subscriptionId, null, 1L, 2L, 3L, 45_000L), "accountId");
        assertInvalid(message(null, 2L, 3L, 45_000L), "equalAllocatedQuantity");
        assertInvalid(message(1L, null, 3L, 45_000L), "proportionalAllocatedQuantity");
        assertInvalid(message(1L, 2L, null, 45_000L), "allocatedQuantity");
        assertInvalid(message(1L, 2L, 3L, null), "allocatedAmount");
    }

    @Test
    void 음수면_거절한다() {
        assertInvalid(message(-1L, 4L, 3L, 45_000L), "equalAllocatedQuantity");
        assertInvalid(message(4L, -1L, 3L, 45_000L), "proportionalAllocatedQuantity");
        assertInvalid(message(0L, 0L, -1L, 45_000L), "allocatedQuantity");
        assertInvalid(message(1L, 2L, 3L, -1L), "allocatedAmount");
    }

    @Test
    void 균등_더하기_비례가_최종_수량과_다르면_거절한다() {
        assertInvalid(message(1L, 2L, 4L, 45_000L), "allocatedQuantity");
    }

    @Test
    void 영주인데_대금이_있거나_배정이_있는데_대금이_0이면_거절한다() {
        assertInvalid(message(0L, 0L, 0L, 15_000L), "allocatedAmount");
        assertInvalid(message(1L, 2L, 3L, 0L), "allocatedAmount");
    }

    private AllocationResultMessage message(Long equal, Long proportional, Long quantity, Long amount) {
        return new AllocationResultMessage(eventId, TOPIC, OCCURRED_AT, ipoId, subscriptionId, accountId,
                equal, proportional, quantity, amount);
    }

    private static void assertInvalid(AllocationResultMessage message, String field) {
        assertThatThrownBy(message::toCommand)
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }
}
