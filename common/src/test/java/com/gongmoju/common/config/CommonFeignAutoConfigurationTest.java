package com.gongmoju.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.common.feign.FeignRetryer;
import feign.Request;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Retryer 빈 등록과 common.feign.retry.* 설정 반영 확인.
// FeignRetryer는 설정값을 노출하지 않으므로 몇 번째 실패에서 예외를 던지는지로 확인한다
class CommonFeignAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CommonFeignAutoConfiguration.class)
            .withBean(ObjectMapper.class);

    @Test
    void 설정이_없으면_재시도_2회_응답_지연도_재시도하는_FeignRetryer를_등록한다() {
        contextRunner.run(context -> {
            Retryer retryer = context.getBean(Retryer.class).clone();
            assertThat(retryer).isInstanceOf(FeignRetryer.class);

            assertThatNoException().isThrownBy(() -> retryer.continueOrPropagate(readTimeout()));
            assertThatNoException().isThrownBy(() -> retryer.continueOrPropagate(readTimeout()));
            assertThatThrownBy(() -> retryer.continueOrPropagate(readTimeout())).isInstanceOf(RetryableException.class);
        });
    }

    @Test
    void yml로_재시도_횟수와_응답_지연_재시도_여부를_바꿀_수_있다() {
        contextRunner
                .withPropertyValues(
                        "common.feign.retry.max-retries=1",
                        "common.feign.retry.interval=1",
                        "common.feign.retry.max-interval=1",
                        "common.feign.retry.retry-on-read-timeout=false")
                .run(context -> {
                    Retryer retryer = context.getBean(Retryer.class).clone();

                    assertThatThrownBy(() -> retryer.continueOrPropagate(readTimeout()))
                            .isInstanceOf(RetryableException.class);
                    assertThatNoException().isThrownBy(() -> retryer.continueOrPropagate(connectTimeout()));
                    assertThatThrownBy(() -> retryer.continueOrPropagate(connectTimeout()))
                            .isInstanceOf(RetryableException.class);
                });
    }

    @Test
    void 서비스가_Retryer_빈을_만들면_그쪽을_사용한다() {
        FeignRetryer serviceRetryer = FeignRetryer.noRetry();
        contextRunner
                .withBean(Retryer.class, () -> serviceRetryer)
                .run(context -> assertThat(context.getBean(Retryer.class)).isSameAs(serviceRetryer));
    }

    private RetryableException readTimeout() {
        return timeout("Read timed out");
    }

    private RetryableException connectTimeout() {
        return timeout("Connect timed out");
    }

    private RetryableException timeout(String message) {
        Request request = Request.create(Request.HttpMethod.GET, "http://localhost/test", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return new RetryableException(-1, message, Request.HttpMethod.GET, new SocketTimeoutException(message),
                (Long) null, request);
    }
}
