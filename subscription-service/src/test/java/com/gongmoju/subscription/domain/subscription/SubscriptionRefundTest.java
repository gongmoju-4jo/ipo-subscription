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

@DisplayName("청약 환불 완료 (ALLOCATED → REFUNDED)")
class SubscriptionRefundTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-10-27T06:59:58.123Z");
    private static final long OFFER_PRICE = 15_000;
    private static final long REQUESTED_QUANTITY = 100;
    private static final long MARGIN_AMOUNT = REQUESTED_QUANTITY * OFFER_PRICE;

    private final Ipo ipo = mock(Ipo.class);

    @BeforeEach
    void setUp() {
        when(ipo.getOfferPrice()).thenReturn(OFFER_PRICE);
    }

    @ParameterizedTest(name = "배정 수량 {0}")
    @CsvSource({"0", "3", "100"})
    @DisplayName("ALLOCATED 청약의 환불을 완료하면 REFUNDED가 되고 환불액이 저장된다 (0주면 증거금 전액, 전부 배정이면 0)")
    void completeRefund_changesToRefunded(long allocatedQuantity) {
        // given
        Subscription subscription = allocated(allocatedQuantity);
        long refundAmount = MARGIN_AMOUNT - allocatedQuantity * OFFER_PRICE;

        // when
        subscription.completeRefund(refundAmount);

        // then
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.REFUNDED);
        assertThat(subscription.getRefundAmount()).isEqualTo(refundAmount);
        assertThat(subscription.getAllocatedQuantity()).isEqualTo(allocatedQuantity);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = SubscriptionStatus.class, names = "ALLOCATED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("ALLOCATED가 아닌 청약의 환불을 완료하려 하면 IllegalStateException이 나고 상태와 환불액은 바뀌지 않는다")
    void completeRefund_throws_whenStatusIsNotAllocated(SubscriptionStatus status) {
        // given
        Subscription subscription = inStatus(status);

        // when, then
        assertThatThrownBy(() -> subscription.completeRefund(MARGIN_AMOUNT)).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(status);
        assertThat(subscription.getRefundAmount()).isNull();
    }

    @ParameterizedTest(name = "환불액 {0}")
    @CsvSource({"1454999", "1455001", "-1", "1500000"})
    @DisplayName("환불액이 증거금 − 배정 대금과 다르면 IllegalArgumentException이 나고 ALLOCATED로 남는다")
    void completeRefund_throws_whenRefundAmountDiffers(long refundAmount) {
        // given
        Subscription subscription = allocated(3);

        // when, then
        assertThatThrownBy(() -> subscription.completeRefund(refundAmount)).isInstanceOf(IllegalArgumentException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ALLOCATED);
        assertThat(subscription.getRefundAmount()).isNull();
    }

    @Test
    @DisplayName("ALLOCATED인데 배정 대금이 없으면 IllegalStateException이 나고 ALLOCATED로 남는다 (데이터 문제, 재시도 대상)")
    void completeRefund_throws_whenAllocatedAmountIsMissing() {
        // given
        Subscription subscription = inStatus(SubscriptionStatus.ALLOCATED);

        // when, then
        assertThatThrownBy(() -> subscription.completeRefund(MARGIN_AMOUNT)).isInstanceOf(IllegalStateException.class);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ALLOCATED);
        assertThat(subscription.getRefundAmount()).isNull();
    }

    @Test
    @DisplayName("배정 대금과 정산 금액, 저장한 환불액과 환불액이 모두 같을 때만 같은 정산 결과로 본다")
    void hasSameRefund_comparesPostedAndRefundAmount() {
        // given
        Subscription subscription = allocated(3);
        subscription.completeRefund(MARGIN_AMOUNT - 45_000);

        // when, then
        assertThat(subscription.hasSameRefund(45_000, MARGIN_AMOUNT - 45_000)).isTrue();
        assertThat(subscription.hasSameRefund(60_000, MARGIN_AMOUNT - 45_000)).isFalse();
        assertThat(subscription.hasSameRefund(45_000, MARGIN_AMOUNT - 60_000)).isFalse();
    }

    @Test
    @DisplayName("환불 전 청약은 배정 대금이 같아도 같은 정산 결과가 아니다")
    void hasSameRefund_returnsFalse_beforeRefund() {
        // given
        Subscription subscription = allocated(3);

        // when, then
        assertThat(subscription.hasSameRefund(45_000, MARGIN_AMOUNT - 45_000)).isFalse();
    }

    private Subscription allocated(long allocatedQuantity) {
        Subscription subscription = inStatus(SubscriptionStatus.CONFIRMED);
        subscription.allocate(allocatedQuantity, allocatedQuantity * OFFER_PRICE);
        return subscription;
    }

    private Subscription inStatus(SubscriptionStatus status) {
        Subscription subscription = Subscription.accept(UUID.randomUUID(), UUID.randomUUID(), ipo, UUID.randomUUID(),
                UUID.randomUUID(), REQUESTED_QUANTITY, MARGIN_AMOUNT, RECEIVED_AT);
        ReflectionTestUtils.setField(subscription, "status", status);
        return subscription;
    }
}
