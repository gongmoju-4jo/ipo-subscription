package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscriptionReader;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 확정 청약 목록을 키셋 방식으로 끝까지 받아서 돌려준다 (API 명세 4.1 호출 쪽 규칙)
// 첫 요청은 afterSubscriptionId 없이, 다음 요청부터 직전 응답의 마지막 subscriptionId를 넣고, hasNext가 false면 끝낸다
// 커서는 받은 순서(청약 서비스 DB 정렬)의 마지막 항목을 그대로 쓴다. PostgreSQL과 자바의 UUID 정렬 순서가 달라 자바에서 다시 비교하지 않는다
// 중간 요청이 실패하면 받던 목록을 버리고 예외를 그대로 던진다 (다음 시도는 처음부터)
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
        ConfirmedSubscriptionKeysetPage page = fetch(ipoId, null);
        if (page.totalElements() == null) {
            throw new IllegalStateException("첫 응답에 확정 청약 전체 건수가 없습니다. ipoId=" + ipoId);
        }
        long totalElements = page.totalElements();
        // 전체 건수만큼 미리 잡아 두어 200만 건을 받는 동안 목록을 여러 번 늘려 복사하지 않는다
        List<ConfirmedSubscription> subscriptions = new ArrayList<>((int) Math.min(totalElements, Integer.MAX_VALUE));
        subscriptions.addAll(toConfirmedSubscriptions(page));
        while (page.hasNext()) {
            // 항목 없이 다음이 있다고 하거나 전체 건수보다 많이 받으면 응답이 잘못된 것이다. 계속 요청하지 않고 멈춘다
            if (page.items().isEmpty() || subscriptions.size() > totalElements) {
                throw new IllegalStateException("확정 청약 목록 응답이 올바르지 않습니다. ipoId=" + ipoId
                        + ", totalElements=" + totalElements + ", received=" + subscriptions.size()
                        + ", lastItemCount=" + page.items().size());
            }
            page = fetch(ipoId, page.items().getLast().subscriptionId());
            subscriptions.addAll(toConfirmedSubscriptions(page));
        }
        if (subscriptions.size() != totalElements) {
            throw new IllegalStateException("확정 청약 건수가 맞지 않습니다. ipoId=" + ipoId
                    + ", totalElements=" + totalElements + ", received=" + subscriptions.size());
        }
        return subscriptions;
    }

    // Circuit Breaker는 요청 1번(Feign 재시도 포함)을 1회로 센다. 4xx는 실패로 세지 않는다 (application.yml)
    private ConfirmedSubscriptionKeysetPage fetch(UUID ipoId, UUID afterSubscriptionId) {
        return circuitBreaker.executeSupplier(() ->
                subscriptionClient.getConfirmedSubscriptions(ipoId, STATUS_CONFIRMED, afterSubscriptionId, PAGE_SIZE).data());
    }

    private List<ConfirmedSubscription> toConfirmedSubscriptions(ConfirmedSubscriptionKeysetPage page) {
        return page.items().stream().map(ConfirmedSubscriptionItem::toConfirmedSubscription).toList();
    }
}
