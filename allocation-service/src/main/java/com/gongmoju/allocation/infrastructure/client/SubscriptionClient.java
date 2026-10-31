package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// 청약 서비스 내부 API (API 명세 4.1). 마감(CLOSED) 이후에만 조회할 수 있고, 정렬은 subscriptionId ASC 고정
// Timeout은 application.yml, 재시도와 오류 변환은 common Feign 설정, Circuit Breaker는 SubscriptionClientAdapter가 감싼다
@FeignClient(name = "subscription-service")
interface SubscriptionClient {

    @GetMapping("/internal/ipos/{ipoId}/subscriptions")
    ApiResponse<PageResponse<ConfirmedSubscriptionItem>> getConfirmedSubscriptions(
            @PathVariable("ipoId") UUID ipoId,
            @RequestParam("status") String status,
            @RequestParam("page") int page,
            @RequestParam("size") int size);
}
