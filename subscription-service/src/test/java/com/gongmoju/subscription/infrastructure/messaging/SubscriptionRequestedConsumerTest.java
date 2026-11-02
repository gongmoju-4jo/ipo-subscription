package com.gongmoju.subscription.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.application.subscription.AcceptResult;
import com.gongmoju.subscription.application.subscription.SubscriptionAcceptService;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("접수 Consumer (변환과 예외 분류)")
class SubscriptionRequestedConsumerTest {

    // Boot가 쓰는 ObjectMapper와 같은 기본값 (모르는 필드는 무시)
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SubscriptionAcceptService acceptService = mock(SubscriptionAcceptService.class);
    private final SubscriptionRequestedConsumer consumer = new SubscriptionRequestedConsumer(objectMapper, acceptService);

    private final UUID requestId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final UUID ipoId = UUID.randomUUID();

    @Test
    @DisplayName("올바른 메시지는 접수 내용으로 변환해서 접수 처리를 호출한다")
    void consume_callsAcceptService_whenPayloadIsValid() throws Exception {
        // given
        when(acceptService.accept(any())).thenReturn(AcceptResult.ACCEPTED);

        // when
        consumer.consume(validJson());

        // then
        ArgumentCaptor<SubscriptionRequested> captor = ArgumentCaptor.forClass(SubscriptionRequested.class);
        verify(acceptService).accept(captor.capture());
        SubscriptionRequested event = captor.getValue();
        assertThat(event.requestId()).isEqualTo(requestId);
        assertThat(event.subscriptionId()).isEqualTo(SubscriptionIdGenerator.generate(userId, requestId));
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.accountId()).isEqualTo(accountId);
        assertThat(event.ipoId()).isEqualTo(ipoId);
        assertThat(event.requestedQuantity()).isEqualTo(100);
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-10-27T06:59:58.123Z"));
    }

    @Test
    @DisplayName("모르는 필드가 있어도 무시하고 정상 처리한다 (명세: 필드 추가 허용)")
    void consume_ignoresUnknownFields() throws Exception {
        // given
        String payload = validJson().replaceFirst("\\{", "{\"newField\":\"value\",");

        // when
        consumer.consume(payload);

        // then
        verify(acceptService).accept(any());
    }

    @ParameterizedTest(name = "본문 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{not json", "null", "[]", "\"문자열\"", "{\"requestedQuantity\":\"abc\"}"})
    @DisplayName("본문이 비었거나 JSON 객체가 아니거나 형식이 틀리면 접수 처리를 호출하지 않고 InvalidEventException이 난다")
    void consume_throwsInvalidEvent_whenPayloadCannotBeRead(String payload) {
        // given

        // when, then
        assertThatThrownBy(() -> consumer.consume(payload)).isInstanceOf(InvalidEventException.class);
        verify(acceptService, never()).accept(any());
    }

    @Test
    @DisplayName("필수 필드가 없으면 접수 처리를 호출하지 않고 InvalidEventException이 난다")
    void consume_throwsInvalidEvent_whenRequiredFieldIsMissing() throws Exception {
        // given
        String withoutUserId = validJson().replace("\"userId\":\"" + userId + "\",", "");

        // when, then
        assertThatThrownBy(() -> consumer.consume(withoutUserId)).isInstanceOf(InvalidEventException.class);
        verify(acceptService, never()).accept(any());
    }

    @Test
    @DisplayName("uk_subscription_active 위반은 그대로 다시 던져서 재시도 대상이 된다")
    void consume_rethrows_whenActiveSubscriptionConstraintViolated() throws Exception {
        // given
        DataIntegrityViolationException violation = integrityViolation("uk_subscription_active");
        when(acceptService.accept(any())).thenThrow(violation);

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson())).isSameAs(violation);
    }

    @ParameterizedTest(name = "제약 {0}")
    @ValueSource(strings = {"subscription_ipo_id_fkey", "subscription_user_id_check", "ukanything_else"})
    @DisplayName("다른 제약 위반은 재시도해도 같은 결과이므로 InvalidEventException으로 바꿔 던진다")
    void consume_throwsInvalidEvent_whenOtherConstraintViolated(String constraintName) throws Exception {
        // given
        DataIntegrityViolationException violation = integrityViolation(constraintName);
        when(acceptService.accept(any())).thenThrow(violation);

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson()))
                .isInstanceOf(InvalidEventException.class)
                .hasCause(violation);
    }

    @Test
    @DisplayName("제약 이름을 알 수 없는 무결성 위반도 InvalidEventException으로 바꿔 던진다")
    void consume_throwsInvalidEvent_whenConstraintNameIsUnknown() throws Exception {
        // given
        when(acceptService.accept(any())).thenThrow(new DataIntegrityViolationException("원인 불명"));

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson())).isInstanceOf(InvalidEventException.class);
    }

    @Test
    @DisplayName("일시적 오류(그 밖의 예외)는 바꾸지 않고 그대로 던져서 재시도 대상이 된다")
    void consume_rethrowsTransientError_unchanged() throws Exception {
        // given
        RuntimeException transientError = new RuntimeException("DB 연결 오류");
        when(acceptService.accept(any())).thenThrow(transientError);

        // when, then
        assertThatThrownBy(() -> consumer.consume(validJson())).isSameAs(transientError);
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException("제약 위반",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraintName));
    }

    private String validJson() throws Exception {
        SubscriptionRequestedMessage message = new SubscriptionRequestedMessage(UUID.randomUUID(),
                SubscriptionRequestedMessage.TOPIC, "2026-10-27T15:59:58.130+09:00", requestId,
                SubscriptionIdGenerator.generate(userId, requestId), userId, accountId, ipoId, 100,
                "2026-10-27T15:59:58.123+09:00");
        return objectMapper.writeValueAsString(message);
    }
}
