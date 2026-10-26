package com.gongmoju.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.feign.FeignErrorDecoder;
import com.gongmoju.common.feign.FeignExceptionHandler;
import com.gongmoju.common.feign.FeignHeaderInterceptor;
import com.gongmoju.common.feign.FeignRetryer;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Feign 공통 설정 자동 등록.
 * <p>
 * Feign 의존성이 있는 서비스에만 헤더 전달, 에러 디코더, 재시도, Feign 예외 처리를 등록한다.
 * 서비스는 {@code com.gongmoju.{서비스}} 패키지만 스캔하므로 자동 설정(AutoConfiguration.imports)으로 등록한다.
 */
@AutoConfiguration
@ConditionalOnClass(name = "feign.Feign")
@Import(FeignExceptionHandler.class)
public class CommonFeignAutoConfiguration {

    /**
     * 요청자 헤더(X-User-Id, X-User-Role)를 Feign 호출에 그대로 전달하는 인터셉터를 등록한다.
     *
     * @return 헤더 전달 인터셉터
     */
    @Bean
    public FeignHeaderInterceptor feignHeaderInterceptor() {
        return new FeignHeaderInterceptor();
    }

    /**
     * 상대 서비스의 오류 응답을 공통 예외로 바꾸는 에러 디코더를 등록한다.
     * <p>
     * 서비스에서 ErrorDecoder 빈을 직접 만들면 그쪽을 사용한다.
     *
     * @param objectMapper 오류 응답 본문을 읽을 ObjectMapper
     * @return 공통 에러 디코더
     */
    @Bean
    @ConditionalOnMissingBean(ErrorDecoder.class)
    public ErrorDecoder feignErrorDecoder(ObjectMapper objectMapper) {
        return new FeignErrorDecoder(objectMapper);
    }

    /**
     * 5xx, 타임아웃, 연결 실패 재시도를 등록한다. 서비스 yml의 {@code common.feign.retry.*}로 바꿀 수 있다.
     * <p>
     * 서비스에서 Retryer 빈을 직접 만들면 그쪽을 사용한다.
     * 이때도 FeignRetryer를 써야 5xx가 FeignServerException으로 나간다.
     * 재시도를 끄려면 {@code max-retries: 0} 또는 {@code FeignRetryer.noRetry()}를 쓴다 ({@code Retryer.NEVER_RETRY} 금지).
     *
     * @param maxRetries         처음 호출을 뺀 재시도 횟수. 0이면 재시도 안 함 (기본 2)
     * @param interval           첫 재시도 전 대기(ms). 순간 장애(재시작 중 요청, 끊긴 커넥션)를 넘기면서 타임아웃(3s/5s)보다 충분히 작은 값 (기본 200)
     * @param maxInterval        대기 상한(ms). 대기는 1.5배씩 늘어나므로 횟수를 늘려도 이 값을 넘지 않는다 (기본 1000)
     * @param retryOnReadTimeout 응답 지연도 재시도할지. 끄면 최악 지연이 read-timeout 1번으로 줄어든다 (기본 true)
     * @return 공통 재시도 정책
     */
    @Bean
    @ConditionalOnMissingBean(Retryer.class)
    public Retryer feignRetryer(
            @Value("${common.feign.retry.max-retries:2}") int maxRetries,
            @Value("${common.feign.retry.interval:200}") long interval,
            @Value("${common.feign.retry.max-interval:1000}") long maxInterval,
            @Value("${common.feign.retry.retry-on-read-timeout:true}") boolean retryOnReadTimeout) {
        return new FeignRetryer(interval, maxInterval, maxRetries + 1, retryOnReadTimeout);
    }
}
