package com.gongmoju.gateway.queue;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 청약 서비스 내부 API(API 명세 4.4) {@code GET /internal/ipos?status=OPEN}에서 청약 중 공모주 목록을 받는다.
 * 게이트웨이는 WebFlux라 Feign 대신 WebClient로 Eureka 서비스 이름(subscription-service)을 부른다.
 * 사용자 요청과 무관하게 {@link OpenIpoCatalog}가 주기적으로 부르므로 사용자 헤더를 보내지 않는다.
 */
@Component
class SubscriptionOpenIpoSource implements OpenIpoSource {

    private static final String PATH = "/internal/ipos?status=OPEN";
    private static final ParameterizedTypeReference<ApiResponse<List<Item>>> RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final WebClient webClient;
    private final OpenIpoProperties properties;

    SubscriptionOpenIpoSource(WebClient.Builder builder, ObjectProvider<ReactorLoadBalancerExchangeFilterFunction> loadBalancer,
                              OpenIpoProperties properties) {
        WebClient.Builder configured = builder.clone().baseUrl(properties.baseUrl());
        if (properties.loadBalanced()) {
            ReactorLoadBalancerExchangeFilterFunction filter = loadBalancer.getIfAvailable();
            if (filter == null) {
                throw new IllegalStateException("queue.open-ipos.load-balanced=true인데 로드밸런서(spring-cloud-loadbalancer)가 없습니다");
            }
            configured.filter(filter);
        }
        this.webClient = configured.build();
        this.properties = properties;
    }

    @Override
    public Mono<List<OpenIpo>> fetch() {
        return webClient.get().uri(PATH)
                .retrieve()
                .bodyToMono(RESPONSE_TYPE)
                .timeout(properties.timeout())
                .map(SubscriptionOpenIpoSource::toOpenIpos);
    }

    // 응답 형식이 명세와 다르면(실패 응답, 빠진 값) 목록 전체를 받지 못한 것으로 본다. 일부만 쓰면 빠진 공모주가 진입을 거절당한다
    private static List<OpenIpo> toOpenIpos(ApiResponse<List<Item>> response) {
        if (!response.success() || response.data() == null) {
            throw new IllegalStateException("청약 중 공모주 목록 응답이 성공이 아닙니다: " + response.error());
        }
        return response.data().stream().map(item -> {
            if (item == null || item.ipoId() == null || item.subscriptionStartAt() == null || item.subscriptionEndAt() == null) {
                throw new IllegalStateException("청약 중 공모주 목록에 빠진 값이 있습니다: " + item);
            }
            return new OpenIpo(item.ipoId(), item.subscriptionStartAt().toInstant(), item.subscriptionEndAt().toInstant());
        }).toList();
    }

    // 청약 서비스 응답 항목 (OpenIpoResponse와 같은 필드)
    record Item(UUID ipoId, OffsetDateTime subscriptionStartAt, OffsetDateTime subscriptionEndAt) {
    }
}
