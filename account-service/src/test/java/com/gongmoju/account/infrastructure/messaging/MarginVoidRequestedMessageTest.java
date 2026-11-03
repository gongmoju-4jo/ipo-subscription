package com.gongmoju.account.infrastructure.messaging;

import com.gongmoju.account.application.margin.VoidMarginCommand;
import com.gongmoju.account.domain.margin.MarginVoidReason;
import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// margin.void-requested 메시지 검증. 재처리해도 결과가 같은 실패는 InvalidEventException (바로 DLT)
class MarginVoidRequestedMessageTest {

    private static final String TOPIC = "margin.void-requested";
    private static final String OCCURRED_AT = "2026-10-27T16:00:29.000+09:00";

    private final UUID eventId = UUID.randomUUID();
    private final UUID subscriptionId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @Test
    void 정상_메시지는_반환_명령으로_바뀐다() {
        VoidMarginCommand command = message(eventId, TOPIC, OCCURRED_AT, subscriptionId, accountId, "TIMEOUT").toCommand();

        assertThat(command).isEqualTo(new VoidMarginCommand(eventId, subscriptionId, accountId, MarginVoidReason.TIMEOUT));
    }

    @Test
    void eventType이_토픽과_다르면_거절한다() {
        assertInvalid(message(eventId, "margin.voided", OCCURRED_AT, subscriptionId, accountId, "TIMEOUT"), "eventType");
    }

    @Test
    void 시각_형식이_틀리면_거절한다() {
        assertInvalid(message(eventId, TOPIC, "2026-10-27 16:00:29", subscriptionId, accountId, "TIMEOUT"), "occurredAt");
    }

    @Test
    void 정해진_값이_아닌_reason은_거절한다() {
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, accountId, "REFUND"), "reason");
    }

    @Test
    void 필수_필드가_없으면_거절한다() {
        assertInvalid(message(null, TOPIC, OCCURRED_AT, subscriptionId, accountId, "TIMEOUT"), "eventId");
        assertInvalid(message(eventId, TOPIC, null, subscriptionId, accountId, "TIMEOUT"), "occurredAt");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, null, accountId, "TIMEOUT"), "subscriptionId");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, null, "TIMEOUT"), "accountId");
        assertInvalid(message(eventId, TOPIC, OCCURRED_AT, subscriptionId, accountId, null), "reason");
    }

    private static void assertInvalid(MarginVoidRequestedMessage message, String field) {
        assertThatThrownBy(message::toCommand)
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining(field);
    }

    private static MarginVoidRequestedMessage message(UUID eventId, String eventType, String occurredAt,
                                                      UUID subscriptionId, UUID accountId, String reason) {
        return new MarginVoidRequestedMessage(eventId, eventType, occurredAt, subscriptionId, accountId, reason);
    }
}
