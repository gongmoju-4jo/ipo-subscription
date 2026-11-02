package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// margin.reserve-requested 메시지 검증 (이벤트 명세 2-1). 재처리해도 결과가 같은 실패는 InvalidEventException (바로 DLT)
class MarginReserveRequestedMessageTest {

    private static final String TOPIC = "margin.reserve-requested";
    private static final String OCCURRED_AT = "2026-10-27T15:59:58.410+09:00";

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID ipoId = UUID.randomUUID();

    @Test
    void 정상_메시지는_인출_명령으로_바뀐다() {
        ReserveMarginCommand command = message(eventId, TOPIC, OCCURRED_AT, subscriptionId, userId, accountId, ipoId, 7_500_000).toCommand();

        assertThat(command).isEqualTo(new ReserveMarginCommand(eventId, subscriptionId, userId, accountId, ipoId, 7_500_000));
    }

    @Test
    void eventType이_토픽과_다르면_거절한다() {
        assertInvalid(message(eventId, "margin.reserved", OCCURRED_AT, subscriptionId, userId, accountId, ipoId, 1_000), "eventType");
    }

    @Test
    void 시각_형식이_틀리면_거절한다() {
        assertInvalid(message(eventId, TOPIC, "2026-10-27 15:59:58", subscriptionId, userId, accountId, ipoId, 1_000), "occurredAt");
    }

    @Test
    void 금액이_1_미만이면_거절한다() {
        // amount가 없으면 0으로 읽혀 여기서 걸린다
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, userId, accountId, ipoId, 0), "amount");
    }

    @Test
    void 필수_ID가_없으면_거절한다() {
        assertInvalid(message(null, TOPIC, OCCURRED_AT, subscriptionId, userId, accountId, ipoId, 1_000), "eventId");
        assertInvalid(message(eventId, TOPIC, null, subscriptionId, userId, accountId, ipoId, 1_000), "occurredAt");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, null, userId, accountId, ipoId, 1_000), "subscriptionId");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, null, accountId, ipoId, 1_000), "userId");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, userId, null, ipoId, 1_000), "accountId");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, userId, accountId, null, 1_000), "ipoId");
    }

    private static void assertInvalid(MarginReserveRequestedMessage message, String field) {
        assertThatThrownBy(message::toCommand)
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }

    private static MarginReserveRequestedMessage message(UUID eventId, String eventType, String occurredAt, UUID subscriptionId,
                                                         UUID userId, UUID accountId, UUID ipoId, long amount) {
        return new MarginReserveRequestedMessage(eventId, eventType, occurredAt, subscriptionId, userId, accountId, ipoId, amount);
    }
}
