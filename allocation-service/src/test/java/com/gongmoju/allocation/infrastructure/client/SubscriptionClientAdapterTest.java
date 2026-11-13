package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.common.feign.FeignServerException;
import com.gongmoju.common.response.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 확정 청약 목록을 키셋 방식으로 끝까지 받기. 청약 서비스 대신 가짜 Feign 클라이언트를 쓴다
class SubscriptionClientAdapterTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");

    private final SubscriptionClient client = mock(SubscriptionClient.class);
    private final SubscriptionClientAdapter adapter = new SubscriptionClientAdapter(client, CircuitBreakerRegistry.ofDefaults());

    // 첫 요청은 커서 없이, 다음 요청은 직전 응답의 마지막 항목 ID로 보내고 받은 순서대로 합친다
    // 커서는 자바 UUID 순서로 다시 고르지 않고 받은 목록의 마지막 항목을 그대로 쓴다 (PostgreSQL과 자바의 UUID 정렬이 달라서)
    @Test
    void combinesAllPagesUsingLastReceivedItemAsCursor() {
        ConfirmedSubscriptionItem first = item("00000000-0000-4000-8000-000000000001", 100);
        ConfirmedSubscriptionItem second = item("ffffffff-0000-4000-8000-000000000002", 200);
        ConfirmedSubscriptionItem third = item("80000000-0000-4000-8000-000000000003", 300);
        givenPage(null, List.of(second, first), true, 3L);
        givenPage(first.subscriptionId(), List.of(third), false, null);

        List<ConfirmedSubscription> result = adapter.readAll(IPO_ID);

        assertThat(result).containsExactly(
                new ConfirmedSubscription(second.subscriptionId(), second.userId(), second.accountId(), 200),
                new ConfirmedSubscription(first.subscriptionId(), first.userId(), first.accountId(), 100),
                new ConfirmedSubscription(third.subscriptionId(), third.userId(), third.accountId(), 300));
        verify(client).getConfirmedSubscriptions(IPO_ID, "CONFIRMED", null, 500);
        verify(client).getConfirmedSubscriptions(IPO_ID, "CONFIRMED", first.subscriptionId(), 500);
        verify(client, times(2)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    // 확정 청약이 없으면 첫 요청만 하고 빈 목록을 돌려준다
    @Test
    void returnsEmptyListWhenThereAreNoConfirmedSubscriptions() {
        givenPage(null, List.of(), false, 0L);

        assertThat(adapter.readAll(IPO_ID)).isEmpty();
        verify(client, times(1)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    // 중간 요청이 실패하면 받던 목록을 버리고 원래 예외를 감싸지 않고 던진다. 그 뒤는 요청하지 않는다
    @Test
    void propagatesMiddlePageFailureWithoutWrapping() {
        FeignServerException failure = new FeignServerException(503, "SERVICE_UNAVAILABLE", "청약 서비스 오류", "getConfirmedSubscriptions");
        ConfirmedSubscriptionItem first = item("00000000-0000-4000-8000-000000000001", 100);
        givenPage(null, List.of(first), true, 3L);
        when(client.getConfirmedSubscriptions(IPO_ID, "CONFIRMED", first.subscriptionId(), 500)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID)).isSameAs(failure);
        verify(client, times(2)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    // 받은 건수가 첫 응답의 totalElements와 다르면 일부만 받은 것으로 보고 예외를 던진다
    @Test
    void failsWhenReceivedCountDiffersFromTotalElements() {
        givenPage(null, List.of(item("00000000-0000-4000-8000-000000000001", 100)), false, 2L);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("totalElements=2")
                .hasMessageContaining("received=1");
    }

    // 첫 응답에 totalElements가 없으면 다 받았는지 확인할 수 없어 예외를 던진다
    @Test
    void failsWhenFirstPageHasNoTotalElements() {
        givenPage(null, List.of(item("00000000-0000-4000-8000-000000000001", 100)), false, null);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("전체 건수가 없습니다");
    }

    // 항목 없이 다음이 있다고 하면 같은 요청을 끝없이 반복하지 않도록 멈춘다
    @Test
    void stopsWhenHasNextWithoutItems() {
        givenPage(null, List.of(), true, 1L);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("올바르지 않습니다");
        verify(client, times(1)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    // 전체 건수보다 많이 받았는데 다음이 있다고 하면 더 요청하지 않고 멈춘다
    @Test
    void stopsWhenReceivedMoreThanTotalElements() {
        givenPage(null, List.of(item("00000000-0000-4000-8000-000000000001", 100),
                item("00000000-0000-4000-8000-000000000002", 200)), true, 1L);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("올바르지 않습니다");
        verify(client, times(1)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    // 다음이 있다면서 마지막 항목이 방금 보낸 커서와 같으면 같은 요청을 반복하지 않고 두 번째 응답에서 멈춘다
    @Test
    void stopsWhenNextCursorDoesNotAdvance() {
        ConfirmedSubscriptionItem first = item("00000000-0000-4000-8000-000000000001", 100);
        givenPage(null, List.of(first), true, 3L);
        givenPage(first.subscriptionId(), List.of(first), true, null);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("커서가 바뀌지 않았습니다");
        verify(client, times(2)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), any(), eq(500));
    }

    private void givenPage(UUID afterSubscriptionId, List<ConfirmedSubscriptionItem> items, boolean hasNext, Long totalElements) {
        when(client.getConfirmedSubscriptions(IPO_ID, "CONFIRMED", afterSubscriptionId, 500))
                .thenReturn(ApiResponse.success(new ConfirmedSubscriptionKeysetPage(items, 500, hasNext, totalElements)));
    }

    private ConfirmedSubscriptionItem item(String subscriptionId, long requestedQuantity) {
        return new ConfirmedSubscriptionItem(UUID.fromString(subscriptionId), UUID.randomUUID(), UUID.randomUUID(), requestedQuantity);
    }
}
