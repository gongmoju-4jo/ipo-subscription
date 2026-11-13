package com.gongmoju.allocation.infrastructure.client;

import com.gongmoju.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

// 청약 서비스 내부 API (API 명세 4.1). 마감(CLOSED) 이후에만 조회할 수 있고, 정렬은 subscriptionId ASC 고정
// 키셋 방식으로 받는다 (page를 보내지 않음, 청약 서비스 #138). 뒤쪽 목록도 앞 행을 건너뛰며 세지 않아 건수가 많아도 느려지지 않는다
// Timeout은 application.yml, 재시도와 오류 변환은 common Feign 설정, Circuit Breaker는 SubscriptionClientAdapter가 감싼다
@FeignClient(name = "subscription-service")
interface SubscriptionClient {

    /**
     * @param afterSubscriptionId 직전 응답의 마지막 subscriptionId. 첫 요청은 null (null이면 파라미터를 보내지 않는다)
     */
    @GetMapping("/internal/ipos/{ipoId}/subscriptions")
    ApiResponse<ConfirmedSubscriptionKeysetPage> getConfirmedSubscriptions(
            @PathVariable("ipoId") UUID ipoId,
            @RequestParam("status") String status,
            @RequestParam(value = "afterSubscriptionId", required = false) UUID afterSubscriptionId,
            @RequestParam("size") int size);
}
