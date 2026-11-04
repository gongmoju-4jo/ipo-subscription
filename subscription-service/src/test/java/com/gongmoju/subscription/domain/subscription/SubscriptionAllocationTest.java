package com.gongmoju.subscription.domain.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("청약 배정 (CONFIRMED → ALLOCATED)")
class SubscriptionAllocationTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");
    private static final long OFFER_PRICE = 15_000;
    private static final long REQUESTED_QUANTITY = 100;

    private final Ipo ipo = mock(Ipo.class);

    @BeforeEach
    void setUp() {
        when(ipo.getOfferPrice()).thenReturn(OFFER_PRICE);
    }

    @ParameterizedTest(name = "배정 수량 {0}")
    @CsvSource({"0", "3", "100"})
    @DisplayName("CONFIRMED 청약을 배정하면 ALLOCATED가 되고 배정 수량, 배정 대금이 저장된다 (0주, 청약 수량 전부 포함)")
    void allocate_changesToAllocated(long allocatedQuantity) {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.CONFIRMED);

        // when
        subscription.allocate(allocatedQuantity, allocatedQuantity * OFFER_PRICE);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ALLOCATED);
        assertThat(subscription.getAllocatedQuantity()).isEqualTo(allocatedQuantity);
        assertThat(subscription.getAllocatedAmount()).isEqualTo(allocatedQuantity * OFFER_PRICE);
        assertThat(subscription.getRefundAmount()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CONFIRMED가 아닌 청약을 배정하려 하면 IllegalStateException이 나고 상태와 배정 결과는 바뀌지 않는다")
    void allocate_throws_whenStatusIsNotConfirmed(SubscriptionStatus status) {
        // given
        Subscription subscription = inStatus(status);

        // when, then
        assertThatThrownBy(() -> subscription.allocate(3, 3 * OFFER_PRICE)).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getAllocatedQuantity()).isNull();
    }

    @ParameterizedTest(name = "배정 수량 {0}, 배정 대금 {1}")
    @CsvSource({
            "-1, -15000",
            "101, 1515000",
            "3, 44999",
            "3, 45001",
            "0, 1"
    })
    @DisplayName("배정 수량이 0 미만이거나 청약 수량보다 크거나, 배정 대금이 배정 수량 × 공모가와 다르면 IllegalArgumentException이 나고 CONFIRMED로 남는다")
    void allocate_throws_whenValuesAreInvalid(long allocatedQuantity, long allocatedAmount) {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.CONFIRMED);

        // when, then
        assertThatThrownBy(() -> subscription.allocate(allocatedQuantity, allocatedAmount)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CONFIRMED);
        assertThat(subscription.getAllocatedQuantity()).isNull();
    }

    @Test
    @DisplayName("저장한 배정 결과와 수량, 대금이 모두 같을 때만 같은 배정 결과로 본다")
    void hasSameAllocation_comparesQuantityAndAmount() {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.CONFIRMED);
        subscription.allocate(3, 3 * OFFER_PRICE);

        // when, then
        assertThat(subscription.hasSameAllocation(3, 3 * OFFER_PRICE)).isTrue();
        assertThat(subscription.hasSameAllocation(4, 3 * OFFER_PRICE)).isFalse();
        assertThat(subscription.hasSameAllocation(3, 4 * OFFER_PRICE)).isFalse();
    }

    @Test
    @DisplayName("배정 전 청약은 어떤 값과도 같은 배정 결과가 아니다")
    void hasSameAllocation_returnsFalse_beforeAllocation() {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.CONFIRMED);

        // when, then
        assertThat(subscription.hasSameAllocation(0, 0)).isFalse();
    }

    private Subscription inStatus(SubscriptionStatus status) {
        Subscription subscription = Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(),
                UUID.randomUUID(), REQUESTED_QUANTITY, REQUESTED_QUANTITY * OFFER_PRICE, RECEIVED_AT);
        ReflectionTestUtils.setField(subscription, "status", status);
        return subscription;
    }
}
