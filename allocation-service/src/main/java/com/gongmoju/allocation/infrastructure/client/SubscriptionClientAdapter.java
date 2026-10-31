package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscriptionReader;
import com.gongmoju.common.response.PageResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 확정 청약 목록을 모든 페이지 받아서 돌려준다 (API 명세 4.1 호출 쪽 규칙)
// 중간 페이지가 실패하면 받던 목록을 버리고 예외를 그대로 던진다 (다음 시도는 처음부터)
// fallback은 두지 않는다. 빈 목록으로 대신하면 청약 0건으로 배정이 끝나 버린다
@Component
class SubscriptionClientAdapter implements ConfirmedSubscriptionReader {

    static final String CIRCUIT_BREAKER_NAME = "subscription-service";
    static final String STATUS_CONFIRMED = "CONFIRMED";
    static final int PAGE_SIZE = 500;

    private final SubscriptionClient subscriptionClient;
    private final CircuitBreaker circuitBreaker;

    SubscriptionClientAdapter(SubscriptionClient subscriptionClient, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.subscriptionClient = subscriptionClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_BREAKER_NAME);
    }

    @Override
    public List<ConfirmedSubscription> readAll(UUID ipoId) {
        PageResponse<ConfirmedSubscriptionItem> first = fetch(ipoId, 0);
        List<ConfirmedSubscription> subscriptions = new ArrayList<>(toConfirmedSubscriptions(first));
        for (int page = 1; page < first.totalPages(); page++) {
            subscriptions.addAll(toConfirmedSubscriptions(fetch(ipoId, page)));
        }
        if (subscriptions.size() != first.totalElements()) {
            throw new IllegalStateException("확정 청약 건수가 맞지 않습니다. ipoId=" + ipoId
                    + ", totalElements=" + first.totalElements() + ", received=" + subscriptions.size());
        }
        return subscriptions;
    }

    // Circuit Breaker는 페이지 호출 1번(Feign 재시도 포함)을 1회로 센다. 4xx는 실패로 세지 않는다 (application.yml)
    private PageResponse<ConfirmedSubscriptionItem> fetch(UUID ipoId, int page) {
        return circuitBreaker.executeSupplier(() ->
                subscriptionClient.getConfirmedSubscriptions(ipoId, STATUS_CONFIRMED, page, PAGE_SIZE).data());
    }

    private List<ConfirmedSubscription> toConfirmedSubscriptions(PageResponse<ConfirmedSubscriptionItem> page) {
        return page.items().stream().map(ConfirmedSubscriptionItem::toConfirmedSubscription).toList();
    }
}
