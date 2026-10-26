package com.gongmoju.common.feign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.response.ApiErrorDetail;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Util;
import feign.codec.ErrorDecoder;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 내부 API(ApiResponse 형식) 응답이 2xx가 아니면 error의 code, message, details를 읽어 예외로 바꾼다.
 * <ul>
 *   <li>4xx → FeignClientException (업무 요청 실패, 재시도 없음)</li>
 *   <li>5xx → FeignServerException (재시도, 보상 대상)</li>
 * </ul>
 * 5xx는 Feign이 재시도하도록 RetryableException으로 감싸 반환하고, 재시도까지 실패하면 FeignRetryer가 FeignServerException으로 꺼내 던진다.
 * Retryer가 FeignRetryer가 아니면({@code Retryer.NEVER_RETRY} 등) 5xx가 RetryableException으로 나간다.
 * 타임아웃, 연결 실패는 이 디코더를 거치지 않고 feign.RetryableException으로 던져진다.
 * 외부 API(Slack 등)는 응답 형식이 다르므로 {@code @FeignClient(configuration = ...)}로 별도 디코더를 지정한다.
 */
@RequiredArgsConstructor
public class FeignErrorDecoder implements ErrorDecoder {

    private final ObjectMapper objectMapper;

    /**
     * 2xx가 아닌 응답을 예외로 바꾼다.
     *
     * @param methodKey 호출한 Feign 메서드 (예외 메시지에 포함)
     * @param response  상대 서비스 응답
     * @return 4xx면 FeignClientException, 5xx면 FeignServerException을 감싼 RetryableException
     */
    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        String code = "UNKNOWN_ERROR";
        String message = response.reason() != null ? response.reason() : "다른 서비스 호출에 실패했습니다.";
        List<ApiErrorDetail> details = new ArrayList<>();

        // ApiResponse 형식이 아닌 응답(로드밸런서 503 등)도 올 수 있어 읽기 실패 시 기본값 사용
        try {
            if (response.body() != null) {
                JsonNode error = objectMapper.readTree(Util.toString(response.body().asReader(StandardCharsets.UTF_8)))
                        .path("error");
                if (error.hasNonNull("code")) {
                    code = error.get("code").asText();
                }
                if (error.hasNonNull("message")) {
                    message = error.get("message").asText();
                }
                for (JsonNode detail : error.path("details")) {
                    details.add(new ApiErrorDetail(detail.path("field").asText(null), detail.path("reason").asText(null)));
                }
            }
        } catch (IOException ignored) {
        }

        if (status >= 400 && status < 500) {
            return new FeignClientException(status, code, message, details);
        }
        FeignServerException serverException = new FeignServerException(status, code, message, methodKey);
        Request request = response.request();
        return new RetryableException(status, serverException.getMessage(), request.httpMethod(), serverException,
                (Long) null, request);
    }
}
