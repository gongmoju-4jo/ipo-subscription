package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("접수 Consumer 저장 전 검증 (검증 2, 3)")
class SubscriptionRequestValidatorTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");

    private final IpoRepository ipoRepository = mock(IpoRepository.class);
    private final SubscriptionRequestValidator validator = new SubscriptionRequestValidator(ipoRepository);

    private final Ipo ipo = mock(Ipo.class);
    private final UUID userId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID ipoId = UUID.randomUUID();
    private SubscriptionRequested event;

    @BeforeEach
    void setUp() {
        event = new SubscriptionRequested(requestId, SubscriptionIdGenerator.generate(userId, requestId), userId,
                UUID.randomUUID(), ipoId, 100, RECEIVED_AT);
        when(ipoRepository.findById(ipoId)).thenReturn(Optional.of(ipo));
        when(ipo.isBeforeSubscriptionEnd(RECEIVED_AT)).thenReturn(true);
        when(ipo.hasFullMarginRate()).thenReturn(true);
    }

    @Test
    @DisplayName("subscriptionId가 맞고 종목이 있으며 마감 전이고 증거금률이 100%이면 종목을 반환한다")
    void validate_returnsIpo_whenEverythingIsValid() {
        // given

        // when
        Ipo validated = validator.validate(event);

        // then
        assertThat(validated).isSameAs(ipo);
    }

    @Test
    @DisplayName("subscriptionId가 userId와 requestId로 계산한 값과 다르면 종목을 조회하지 않고 InvalidEventException이 난다 (검증 2)")
    void validate_throws_whenSubscriptionIdDoesNotMatch() {
        // given
        SubscriptionRequested tampered = new SubscriptionRequested(requestId, UUID.randomUUID(), userId,
                event.accountId(), ipoId, 100, RECEIVED_AT);

        // when, then
        assertThatThrownBy(() -> validator.validate(tampered))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("subscriptionId");
        verifyNoInteractions(ipoRepository);
    }

    @Test
    @DisplayName("종목이 없으면 InvalidEventException이 난다 (검증 3)")
    void validate_throws_whenIpoDoesNotExist() {
        // given
        when(ipoRepository.findById(ipoId)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("종목");
    }

    @Test
    @DisplayName("receivedAt이 청약 마감 시각과 같거나 이후이면 InvalidEventException이 난다 (검증 3)")
    void validate_throws_whenReceivedAtIsNotBeforeEnd() {
        // given
        when(ipo.isBeforeSubscriptionEnd(RECEIVED_AT)).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("receivedAt");
    }

    @Test
    @DisplayName("증거금률이 100%가 아닌 종목이면 InvalidEventException이 난다 (검증 3)")
    void validate_throws_whenMarginRateIsNotFull() {
        // given
        when(ipo.hasFullMarginRate()).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> validator.validate(event))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("증거금률");
    }
}
