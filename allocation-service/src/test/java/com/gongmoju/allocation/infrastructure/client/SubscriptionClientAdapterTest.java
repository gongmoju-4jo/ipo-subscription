package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.common.feign.FeignServerException;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 확정 청약 목록 모든 페이지 받기. 청약 서비스 대신 가짜 Feign 클라이언트를 쓴다
class SubscriptionClientAdapterTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");

    private final SubscriptionClient client = mock(SubscriptionClient.class);
    private final SubscriptionClientAdapter adapter = new SubscriptionClientAdapter(client, CircuitBreakerRegistry.ofDefaults());

    // 모든 페이지를 순서대로 합치고, 응답 항목을 ConfirmedSubscription으로 바꾼다
    @Test
    void combinesAllPagesInOrder() {
        ConfirmedSubscriptionItem first = item(100);
        ConfirmedSubscriptionItem second = item(200);
        ConfirmedSubscriptionItem third = item(300);
        givenPage(0, List.of(first, second), 3, 2);
        givenPage(1, List.of(third), 3, 2);

        List<ConfirmedSubscription> result = adapter.readAll(IPO_ID);

        assertThat(result).containsExactly(
                new ConfirmedSubscription(first.subscriptionId(), first.userId(), first.accountId(), 100),
                new ConfirmedSubscription(second.subscriptionId(), second.userId(), second.accountId(), 200),
                new ConfirmedSubscription(third.subscriptionId(), third.userId(), third.accountId(), 300));
        verify(client).getConfirmedSubscriptions(IPO_ID, "CONFIRMED", 0, 500);
        verify(client).getConfirmedSubscriptions(IPO_ID, "CONFIRMED", 1, 500);
        verify(client, times(2)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), anyInt(), eq(500));
    }

    // 확정 청약이 없으면 첫 페이지만 받고 빈 목록을 돌려준다
    @Test
    void returnsEmptyListWhenThereAreNoConfirmedSubscriptions() {
        givenPage(0, List.of(), 0, 0);

        assertThat(adapter.readAll(IPO_ID)).isEmpty();
        verify(client, times(1)).getConfirmedSubscriptions(eq(IPO_ID), eq("CONFIRMED"), anyInt(), eq(500));
    }

    // 중간 페이지가 실패하면 받던 목록을 버리고 원래 예외를 감싸지 않고 던진다. 남은 페이지는 요청하지 않는다
    @Test
    void propagatesMiddlePageFailureWithoutWrapping() {
        FeignServerException failure = new FeignServerException(503, "SERVICE_UNAVAILABLE", "청약 서비스 오류", "getConfirmedSubscriptions");
        givenPage(0, List.of(item(100)), 3, 3);
        when(client.getConfirmedSubscriptions(IPO_ID, "CONFIRMED", 1, 500)).thenThrow(failure);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID)).isSameAs(failure);
        verify(client, never()).getConfirmedSubscriptions(IPO_ID, "CONFIRMED", 2, 500);
    }

    // 받은 건수가 첫 페이지의 totalElements와 다르면 일부만 받은 것으로 보고 예외를 던진다
    @Test
    void failsWhenReceivedCountDiffersFromTotalElements() {
        givenPage(0, List.of(item(100)), 2, 1);

        assertThatThrownBy(() -> adapter.readAll(IPO_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("totalElements=2")
                .hasMessageContaining("received=1");
    }

    private void givenPage(int page, List<ConfirmedSubscriptionItem> items, long totalElements, int totalPages) {
        when(client.getConfirmedSubscriptions(IPO_ID, "CONFIRMED", page, 500))
                .thenReturn(ApiResponse.success(new PageResponse<>(items, page, 500, totalElements, totalPages)));
    }

    private ConfirmedSubscriptionItem item(long requestedQuantity) {
        return new ConfirmedSubscriptionItem(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), requestedQuantity);
    }
}
