package com.gongmoju.subscription.infrastructure.messaging;

import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("subscription.requested 메시지를 접수 내용으로 변환 (검증 1)")
class SubscriptionRequestedMessageTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID SUBSCRIPTION_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID IPO_ID = UUID.randomUUID();

    @Test
    @DisplayName("필수 필드가 모두 있고 형식이 맞으면 접수 내용으로 변환하고, +09:00 시각을 Instant로 바꾼다")
    void toEvent_convertsMessage_whenValid() {
        // given
        SubscriptionRequestedMessage message = valid();

        // when
        SubscriptionRequested event = message.toEvent();

        // then
        assertThat(event.requestId()).isEqualTo(REQUEST_ID);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION_ID);
        assertThat(event.userId()).isEqualTo(USER_ID);
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.ipoId()).isEqualTo(IPO_ID);
        assertThat(event.requestedQuantity()).isEqualTo(100);
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-10-27T06:59:58.123Z"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMessages")
    @DisplayName("필수 필드가 없거나 형식이 틀리거나 수량이 1보다 작으면 InvalidEventException이 난다")
    void toEvent_throws_whenInvalid(String caseName, UnaryOperator<SubscriptionRequestedMessage> breaker) {
        // given
        SubscriptionRequestedMessage message = breaker.apply(valid());

        // when, then
        assertThatThrownBy(message::toEvent).isInstanceOf(InvalidEventException.class);
    }

    static Stream<Object[]> invalidMessages() {
        return Stream.of(
                invalid("eventId 없음", m -> new SubscriptionRequestedMessage(null, m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("eventType 없음", m -> new SubscriptionRequestedMessage(m.eventId(), null, m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("eventType이 토픽 이름과 다름", m -> new SubscriptionRequestedMessage(m.eventId(), "margin.reserved", m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("occurredAt 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), null,
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("occurredAt 형식 오류", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), "어제",
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("requestId 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        null, m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("subscriptionId 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), null, m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("userId 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), null, m.accountId(), m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("accountId 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), null, m.ipoId(), m.requestedQuantity(), m.receivedAt())),
                invalid("ipoId 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), null, m.requestedQuantity(), m.receivedAt())),
                invalid("수량 0 (필드가 빠져도 0으로 읽힘)", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), 0, m.receivedAt())),
                invalid("수량 음수", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), -1, m.receivedAt())),
                invalid("receivedAt 없음", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), null)),
                invalid("receivedAt 형식 오류", m -> new SubscriptionRequestedMessage(m.eventId(), m.eventType(), m.occurredAt(),
                        m.requestId(), m.subscriptionId(), m.userId(), m.accountId(), m.ipoId(), m.requestedQuantity(), "2026-10-27 15:59:58"))
        );
    }

    private static Object[] invalid(String caseName, UnaryOperator<SubscriptionRequestedMessage> breaker) {
        return new Object[]{caseName, breaker};
    }

    private static SubscriptionRequestedMessage valid() {
        return new SubscriptionRequestedMessage(EVENT_ID, SubscriptionRequestedMessage.TOPIC,
                "2026-10-27T15:59:58.130+09:00", REQUEST_ID, SUBSCRIPTION_ID, USER_ID, ACCOUNT_ID, IPO_ID,
                100, "2026-10-27T15:59:58.123+09:00");
    }
}
