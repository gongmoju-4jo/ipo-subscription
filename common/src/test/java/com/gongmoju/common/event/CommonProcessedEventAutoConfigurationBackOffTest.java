package com.gongmoju.common.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 서비스나 테스트가 {@link ProcessedEventRepository}를 직접 등록했을 때 자동 설정의 기본 구현이 물러나는지 확인함. 컨테이너 없이 실행된다.
 */
@DisplayName("처리 기록 자동 설정: 같은 타입의 빈이 있으면 물러남")
class CommonProcessedEventAutoConfigurationBackOffTest {

    // 기본 구현이 필요로 하는 JPA Repository는 가짜로 채운다
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonProcessedEventAutoConfiguration.class))
            .withBean(ProcessedEventJpaRepository.class, () -> mock(ProcessedEventJpaRepository.class));

    @Test
    @DisplayName("같은 타입의 빈이 없으면 자동 설정이 기본 구현을 등록한다")
    void registersDefaultImplementation_whenNoCustomBean() {
        // given, when, then
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ProcessedEventRepository.class);
            assertThat(context.getBean(ProcessedEventRepository.class))
                    .isInstanceOf(ProcessedEventRepositoryAdapter.class);
        });
    }

    @Test
    @DisplayName("ProcessedEventRepository를 직접 등록하면 기본 구현은 등록되지 않고 직접 등록한 것만 남는다")
    void backsOff_whenCustomRepositoryExists() {
        // given
        ProcessedEventRepository custom = (eventId, consumerName, topic) -> true;

        // when, then
        contextRunner
                .withBean("customProcessedEventRepository", ProcessedEventRepository.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasSingleBean(ProcessedEventRepository.class);
                    assertThat(context.getBean(ProcessedEventRepository.class)).isSameAs(custom);
                });
    }
}
