package com.gongmoju.common.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OutboxPublisherProperties: 실행 시간 상한과 락 최대 시간")
class OutboxPublisherPropertiesTest {

    @Test
    @DisplayName("설정이 없으면 기본값(상한 30초, 락 최대 1분)으로 묶인다")
    void bindsDefaultsWhenNothingConfigured() {
        // when
        OutboxPublisherProperties properties = bind(Map.of());

        // then
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.maxRunDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.lockAtMostFor()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("application.yml의 키(max-run-duration, lock-at-most-for)로 값을 바꿀 수 있다")
    void bindsConfiguredValues() {
        // when
        OutboxPublisherProperties properties = bind(Map.of(
                "outbox.publisher.send-timeout", "10s",
                "outbox.publisher.max-run-duration", "PT20S",
                "outbox.publisher.lock-at-most-for", "PT2M"));

        // then
        assertThat(properties.maxRunDuration()).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.lockAtMostFor()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("청약 서비스 값(send-timeout 10초)과 기본 상한, 락 최대 시간은 함께 쓸 수 있다 (30 + 5 + 10 = 45초 < 1분)")
    void acceptsSubscriptionServiceValues() {
        assertThatCode(() -> bind(Map.of("outbox.publisher.send-timeout", "10s"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("상한 + 전송 호출 막힘 + send-timeout이 락 최대 시간 이상이면 기동하지 않는다 (실행 중 락이 풀려 동시 발행될 수 있음)")
    void rejectsWhenLongestRunReachesLockAtMostFor() {
        // 50 + 5 + 5 = 60초 = 락 최대 시간
        assertThatThrownBy(() -> new OutboxPublisherProperties(100, 5, Duration.ofSeconds(5), Duration.ofSeconds(50), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lock-at-most-for");
        // 설정으로 넣어도 기동 단계에서 실패한다
        assertThatThrownBy(() -> bind(Map.of("outbox.publisher.max-run-duration", "50s")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("상한이 0 이하면 기동하지 않는다")
    void rejectsNonPositiveMaxRunDuration() {
        assertThatThrownBy(() -> new OutboxPublisherProperties(100, 5, Duration.ofSeconds(5), Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-run-duration");
    }

    @Test
    @DisplayName("발행기 락의 이름, 최대 시간 키와 기본값이 설정 기본값과 같다 (어노테이션과 설정이 같은 값을 읽음)")
    void schedulerLockAnnotationMatchesProperties() throws Exception {
        // given
        SchedulerLock lock = OutboxPublisher.class.getDeclaredMethod("publishPending").getAnnotation(SchedulerLock.class);

        // then
        assertThat(lock.name()).isEqualTo("OutboxPublisher.publishPending");
        assertThat(lock.lockAtMostFor()).isEqualTo("${outbox.publisher.lock-at-most-for:PT1M}");
        assertThat(Duration.parse("PT1M")).isEqualTo(bind(Map.of()).lockAtMostFor());
        // 락 최소 시간은 두지 않는다 (끝나면 바로 놓아 발행 지연이 늘지 않게)
        assertThat(lock.lockAtLeastFor()).isEmpty();
    }

    private OutboxPublisherProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bindOrCreate("outbox.publisher", Bindable.of(OutboxPublisherProperties.class));
    }
}
